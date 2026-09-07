/*
 * sysfs_monitor.c — Reads thermal zones, battery stats, and CPU info from sysfs.
 *
 * Scans /sys/class/thermal/thermal_zone* for temperature data,
 * /sys/class/power_supply/battery/ for battery stats, and
 * /sys/devices/system/cpu/cpuNN/cpufreq/ for governor/frequency info.
 * All paths are encrypted via string_enc.h to resist reverse engineering.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#include "sysfs_monitor.h"
#include "../lib/string_enc.h"

#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>
#include <time.h>
#include <errno.h>
#include <sys/stat.h>

#define TAG "ZSysFs"

/* ---- Internal cached paths ---- */

typedef struct {
    char thermal_base[MAX_PATH_LEN];
    char battery_base[MAX_PATH_LEN];
    char cpu_base[MAX_PATH_LEN];
    char thermal_zone_paths[MAX_THERMAL_ZONES][MAX_PATH_LEN];
    int  thermal_zone_ids[MAX_THERMAL_ZONES];
    char cpu_governor_paths[MAX_CORES][MAX_PATH_LEN];
    char cpu_freq_paths[MAX_CORES][MAX_PATH_LEN];
    char cpu_max_freq_paths[MAX_CORES][MAX_PATH_LEN];
    char cpu_min_freq_paths[MAX_CORES][MAX_PATH_LEN];
    char cpu_scaling_max_paths[MAX_CORES][MAX_PATH_LEN];
    int  initialized;
    int  thermal_zone_count;
    int  core_count;
} sysfs_cache_t;

static sysfs_cache_t g_cache = {0};

/* ---- Helpers ---- */

int sysfs_read_file(const char *path, char *buf, size_t buflen)
{
    FILE *f = fopen(path, "r");
    if (!f) return -1;

    size_t n = fread(buf, 1, buflen - 1, f);
    fclose(f);

    if (n == 0) return -1;

    /* trim trailing whitespace/newlines */
    while (n > 0 && (buf[n-1] == '\n' || buf[n-1] == '\r' || buf[n-1] == ' '))
        n--;
    buf[n] = '\0';
    return (int)n;
}

static int read_int_file(const char *path)
{
    char buf[64];
    if (sysfs_read_file(path, buf, sizeof(buf)) < 0) return -1;
    return atoi(buf);
}

static int64_t monotonic_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ---- Init: discover zones and cores ---- */

int sysfs_monitor_init(void)
{
    DIR *d;
    struct dirent *ent;

    if (g_cache.initialized) return 0;

    /* Thermal zones: /sys/class/thermal/thermal_zone* */
    {
        char base[MAX_PATH_LEN];
        USE(ENC("/sys/class/thermal"), base, sizeof(base));
        strncpy(g_cache.thermal_base, base, sizeof(g_cache.thermal_base) - 1);

        d = opendir(g_cache.thermal_base);
        if (d) {
            g_cache.thermal_zone_count = 0;
            while ((ent = readdir(d)) != NULL && g_cache.thermal_zone_count < MAX_THERMAL_ZONES) {
                if (strncmp(ent->d_name, "thermal_zone", 12) != 0) continue;

                int zone_id = atoi(ent->d_name + 12);
                char zone_path[MAX_PATH_LEN];
                snprintf(zone_path, sizeof(zone_path), "%s/%s",
                         g_cache.thermal_base, ent->d_name);

                strncpy(g_cache.thermal_zone_paths[g_cache.thermal_zone_count],
                        zone_path, MAX_PATH_LEN - 1);
                g_cache.thermal_zone_ids[g_cache.thermal_zone_count] = zone_id;
                g_cache.thermal_zone_count++;
            }
            closedir(d);
        }
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "Found %d thermal zones", g_cache.thermal_zone_count);
    }

    /* CPU cores: /sys/devices/system/cpu/cpu[0-7] */
    {
        char cpu_base[MAX_PATH_LEN];
        USE(ENC("/sys/devices/system/cpu"), cpu_base, sizeof(cpu_base));
        strncpy(g_cache.cpu_base, cpu_base, sizeof(g_cache.cpu_base) - 1);

        g_cache.core_count = 0;
        for (int i = 0; i < MAX_CORES && g_cache.core_count < MAX_CORES; i++) {
            char freq_dir[MAX_PATH_LEN];
            snprintf(freq_dir, sizeof(freq_dir), "%s/cpu%d/cpufreq",
                     g_cache.cpu_base, i);

            struct stat st;
            if (stat(freq_dir, &st) != 0) continue;

            snprintf(g_cache.cpu_governor_paths[i], MAX_PATH_LEN,
                     "%s/cpu%d/cpufreq/scaling_governor", g_cache.cpu_base, i);
            snprintf(g_cache.cpu_freq_paths[i], MAX_PATH_LEN,
                     "%s/cpu%d/cpufreq/scaling_cur_freq", g_cache.cpu_base, i);
            snprintf(g_cache.cpu_max_freq_paths[i], MAX_PATH_LEN,
                     "%s/cpu%d/cpufreq/scaling_max_freq", g_cache.cpu_base, i);
            snprintf(g_cache.cpu_min_freq_paths[i], MAX_PATH_LEN,
                     "%s/cpu%d/cpufreq/scaling_min_freq", g_cache.cpu_base, i);
            snprintf(g_cache.cpu_scaling_max_paths[i], MAX_PATH_LEN,
                     "%s/cpu%d/cpufreq/cpuinfo_max_freq", g_cache.cpu_base, i);

            g_cache.core_count++;
        }
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "Found %d CPU cores", g_cache.core_count);
    }

    /* Battery: /sys/class/power_supply/battery/ */
    {
        char batt_base[MAX_PATH_LEN];
        USE(ENC("/sys/class/power_supply/battery"), batt_base, sizeof(batt_base));
        strncpy(g_cache.battery_base, batt_base, sizeof(g_cache.battery_base) - 1);
    }

    g_cache.initialized = 1;
    return 0;
}

/* ---- Read current snapshot ---- */

int sysfs_monitor_read(sysfs_snapshot_t *snap)
{
    char val[256];
    int i;

    if (!g_cache.initialized) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Not initialized");
        return -1;
    }

    memset(snap, 0, sizeof(*snap));
    snap->timestamp_ms = monotonic_ms();

    /* Thermal zones */
    snap->zone_count = g_cache.thermal_zone_count;
    for (i = 0; i < g_cache.thermal_zone_count; i++) {
        char path[MAX_PATH_LEN];
        thermal_zone_t *z = &snap->zones[i];

        z->zone_id = g_cache.thermal_zone_ids[i];

        /* type */
        snprintf(path, sizeof(path), "%s/type",
                 g_cache.thermal_zone_paths[i]);
        if (sysfs_read_file(path, val, sizeof(val)) >= 0) {
            strncpy(z->type, val, sizeof(z->type) - 1);
        }

        /* temperature (millidegrees) */
        snprintf(path, sizeof(path), "%s/temp",
                 g_cache.thermal_zone_paths[i]);
        z->temp_milli = read_int_file(path);

        /* trip point */
        snprintf(path, sizeof(path), "%s/trip_point_0_temp",
                 g_cache.thermal_zone_paths[i]);
        z->trip_point = read_int_file(path);

        /* mode */
        snprintf(path, sizeof(path), "%s/mode",
                 g_cache.thermal_zone_paths[i]);
        z->mode = read_int_file(path);
    }

    /* Battery */
    {
        battery_info_t *b = &snap->battery;
        char path[MAX_PATH_LEN];

        snprintf(path, sizeof(path), "%s/status",
                 g_cache.battery_base);
        if (sysfs_read_file(path, val, sizeof(val)) >= 0) {
            b->online = (strcmp(val, "Charging") == 0) ? 1 : 0;
        }

        snprintf(path, sizeof(path), "%s/capacity",
                 g_cache.battery_base);
        b->capacity_pct = read_int_file(path);

        snprintf(path, sizeof(path), "%s/current_now",
                 g_cache.battery_base);
        b->current_now_ua = read_int_file(path);

        snprintf(path, sizeof(path), "%s/voltage_now",
                 g_cache.battery_base);
        b->voltage_now_uv = read_int_file(path);

        snprintf(path, sizeof(path), "%s/temp",
                 g_cache.battery_base);
        b->temp_deci = read_int_file(path);

        snprintf(path, sizeof(path), "%s/charge_full",
                 g_cache.battery_base);
        b->charge_full_ua = read_int_file(path);

        snprintf(path, sizeof(path), "%s/charge_now",
                 g_cache.battery_base);
        b->charge_now_ua = read_int_file(path);
    }

    /* CPU cores */
    snap->core_count = g_cache.core_count;
    for (i = 0; i < g_cache.core_count; i++) {
        cpu_core_info_t *c = &snap->cores[i];

        if (sysfs_read_file(g_cache.cpu_governor_paths[i], val, sizeof(val)) >= 0) {
            strncpy(c->governor, val, sizeof(c->governor) - 1);
        }

        c->freq_khz = (uint32_t)read_int_file(g_cache.cpu_freq_paths[i]);
        c->max_freq_khz = (uint32_t)read_int_file(g_cache.cpu_max_freq_paths[i]);
        c->min_freq_khz = (uint32_t)read_int_file(g_cache.cpu_min_freq_paths[i]);
        c->scaling_max_khz = (uint32_t)read_int_file(g_cache.cpu_scaling_max_paths[i]);
    }

    return 0;
}
