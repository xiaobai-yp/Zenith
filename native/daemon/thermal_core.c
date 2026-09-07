/*
 * thermal_core.c — Applies thermal profiles by writing governor and frequency
 * limits to sysfs. Handles per-core governor switching, thermal zone limit
 * enforcement, and profile management.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#include "thermal_core.h"
#include "sysfs_monitor.h"
#include "../lib/string_enc.h"

#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>
#include <errno.h>

#define TAG "ZThermal"

/* ---- Internal state ---- */

static thermal_profile_t g_profiles[MAX_PROFILES];
static int g_profile_count = 0;
static char g_active_profile[MAX_PROFILE_NAME] = {0};

/* Sysfs paths (populated during init) */
static char g_governor_paths[MAX_CORES][MAX_PATH_LEN];
static char g_scaling_max_paths[MAX_CORES][MAX_PATH_LEN];
static char g_scaling_min_paths[MAX_CORES][MAX_PATH_LEN];
static int  g_core_count = 0;
static int  g_initialized = 0;

/* Thermal zone write paths */
static char g_zone_mode_paths[MAX_THERMAL_ZONES][MAX_PATH_LEN];
static int  g_zone_count = 0;

/* ---- Helpers ---- */

static int write_sysfs(const char *path, const char *value)
{
    FILE *f = fopen(path, "w");
    if (!f) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Cannot open %s for writing: %s", path, strerror(errno));
        return -1;
    }
    size_t len = strlen(value);
    size_t written = fwrite(value, 1, len, f);
    fclose(f);
    if (written != len) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Short write to %s: %zu/%zu", path, written, len);
        return -1;
    }
    __android_log_print(ANDROID_LOG_DEBUG, TAG,
                        "Wrote '%s' to %s", value, path);
    return 0;
}

static int write_sysfs_int(const char *path, int value)
{
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", value);
    return write_sysfs(path, buf);
}

/* ---- Init ---- */

int thermal_core_init(void)
{
    char cpu_base[MAX_PATH_LEN];
    char thermal_base[MAX_PATH_LEN];

    if (g_initialized) return 0;

    USE(ENC("/sys/devices/system/cpu"), cpu_base, sizeof(cpu_base));

    /* Discover writable CPU scaling paths */
    g_core_count = 0;
    for (int i = 0; i < MAX_CORES; i++) {
        char path[MAX_PATH_LEN];

        snprintf(path, sizeof(path), "%s/cpu%d/cpufreq/scaling_governor",
                 cpu_base, i);
        if (access(path, W_OK) == 0) {
            strncpy(g_governor_paths[i], path, MAX_PATH_LEN - 1);

            snprintf(path, sizeof(path), "%s/cpu%d/cpufreq/scaling_max_freq",
                     cpu_base, i);
            strncpy(g_scaling_max_paths[i], path, MAX_PATH_LEN - 1);

            snprintf(path, sizeof(path), "%s/cpu%d/cpufreq/scaling_min_freq",
                     cpu_base, i);
            strncpy(g_scaling_min_paths[i], path, MAX_PATH_LEN - 1);

            g_core_count++;
        }
    }

    /* Discover thermal zone cooling device paths */
    USE(ENC("/sys/class/thermal"), thermal_base, sizeof(thermal_base));
    g_zone_count = 0;
    {
        DIR *d = opendir(thermal_base);
        if (d) {
            struct dirent *ent;
            while ((ent = readdir(d)) != NULL && g_zone_count < MAX_THERMAL_ZONES) {
                if (strncmp(ent->d_name, "thermal_zone", 12) != 0) continue;

                char path[MAX_PATH_LEN];
                snprintf(path, sizeof(path), "%s/%s/mode",
                         thermal_base, ent->d_name);
                if (access(path, W_OK) == 0) {
                    strncpy(g_zone_mode_paths[g_zone_count], path, MAX_PATH_LEN - 1);
                    g_zone_count++;
                }
            }
            closedir(d);
        }
    }

    g_initialized = 1;
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Init: %d cores, %d writable zones",
                        g_core_count, g_zone_count);
    return 0;
}

/* ---- Profile management ---- */

int thermal_core_register_profile(const thermal_profile_t *profile)
{
    if (!profile || !profile->id[0]) return -1;
    if (g_profile_count >= MAX_PROFILES) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Profile table full");
        return -1;
    }

    /* Replace if same id exists */
    for (int i = 0; i < g_profile_count; i++) {
        if (strcmp(g_profiles[i].id, profile->id) == 0) {
            g_profiles[i] = *profile;
            __android_log_print(ANDROID_LOG_DEBUG, TAG,
                                "Updated profile: %s", profile->id);
            return 0;
        }
    }

    g_profiles[g_profile_count] = *profile;
    g_profiles[g_profile_count].active = 0;
    g_profile_count++;
    __android_log_print(ANDROID_LOG_DEBUG, TAG,
                        "Registered profile: %s (%d total)",
                        profile->id, g_profile_count);
    return 0;
}

const char *thermal_core_get_active_profile(void)
{
    if (g_active_profile[0] == '\0') return NULL;
    return g_active_profile;
}

/* ---- Apply profile ---- */

static int find_profile(const char *profile_id)
{
    for (int i = 0; i < g_profile_count; i++) {
        if (strcmp(g_profiles[i].id, profile_id) == 0)
            return i;
    }
    return -1;
}

int thermal_core_apply_profile(const char *profile_id)
{
    int idx = find_profile(profile_id);
    if (idx < 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Profile not found: %s", profile_id);
        return -1;
    }

    thermal_profile_t *p = &g_profiles[idx];

    /* Mark active */
    for (int i = 0; i < g_profile_count; i++)
        g_profiles[i].active = 0;
    p->active = 1;
    strncpy(g_active_profile, p->id, sizeof(g_active_profile) - 1);

    /* Apply governor per-cluster */
    for (int i = 0; i < g_core_count; i++) {
        /* Determine which cluster this core belongs to */
        const char *gov = NULL;
        uint32_t max_f = p->max_freq_khz;
        uint32_t min_f = p->min_freq_khz;

        if (i <= 3) {
            /* policy0 cluster (little cores) */
            gov = p->governor[0] ? p->governor : NULL;
        } else if (i <= 6) {
            /* policy4 cluster (big cores) */
            gov = p->governor4[0] ? p->governor4 : (p->governor[0] ? p->governor : NULL);
            if (p->max_freq4_khz > 0) max_f = p->max_freq4_khz;
            if (p->min_freq4_khz > 0) min_f = p->min_freq4_khz;
        } else {
            /* policy7 cluster (prime core) */
            gov = p->governor7[0] ? p->governor7 : (p->governor[0] ? p->governor : NULL);
            if (p->max_freq7_khz > 0) max_f = p->max_freq7_khz;
            if (p->min_freq7_khz > 0) min_f = p->min_freq7_khz;
        }

        if (gov && gov[0]) {
            write_sysfs(g_governor_paths[i], gov);
        }
        if (max_f > 0) {
            write_sysfs_int(g_scaling_max_paths[i], (int)max_f);
        }
        if (min_f > 0) {
            write_sysfs_int(g_scaling_min_paths[i], (int)min_f);
        }
    }

    /* Apply thermal zone limits */
    for (int t = 0; t < p->thermal_limit_count && t < g_zone_count; t++) {
        if (p->thermal_limit_milli[t] > 0) {
            write_sysfs_int(g_zone_mode_paths[t], p->thermal_limit_milli[t]);
        }
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Applied profile: %s (gov=%s max=%u kHz)",
                        p->id, p->governor, p->max_freq_khz);
    return 0;
}

int thermal_core_set_limit(int zone_id, int temp_milli)
{
    if (zone_id < 0 || zone_id >= g_zone_count) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Invalid zone_id: %d", zone_id);
        return -1;
    }

    if (temp_milli == 0) {
        /* Disable limit: set mode to enabled (1) */
        write_sysfs_int(g_zone_mode_paths[zone_id], 1);
    } else {
        write_sysfs_int(g_zone_mode_paths[zone_id], temp_milli);
    }

    __android_log_print(ANDROID_LOG_DEBUG, TAG,
                        "Zone %d limit set to %d mC", zone_id, temp_milli);
    return 0;
}

int thermal_core_set_governor(int core_id, const char *governor)
{
    if (!governor || governor[0] == '\0') return -1;

    if (core_id == -1) {
        /* Apply to all cores */
        for (int i = 0; i < g_core_count; i++) {
            write_sysfs(g_governor_paths[i], governor);
        }
    } else if (core_id >= 0 && core_id < g_core_count) {
        write_sysfs(g_governor_paths[core_id], governor);
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Invalid core_id: %d", core_id);
        return -1;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Governor set to '%s' on core %d",
                        governor, core_id);
    return 0;
}

int thermal_core_set_max_freq(int core_id, uint32_t freq_khz)
{
    if (core_id == -1) {
        for (int i = 0; i < g_core_count; i++) {
            write_sysfs_int(g_scaling_max_paths[i], (int)freq_khz);
        }
    } else if (core_id >= 0 && core_id < g_core_count) {
        write_sysfs_int(g_scaling_max_paths[core_id], (int)freq_khz);
    } else {
        return -1;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Max freq set to %u kHz on core %d",
                        freq_khz, core_id);
    return 0;
}
