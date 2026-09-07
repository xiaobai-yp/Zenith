/*
 * profile_engine.c — Loads profiles.json, maps package→profile_id.
 *
 * Parses the JSON profile config (minimal parser, no external deps),
 * registers each profile with thermal_core, and provides package→profile
 * mapping for the app monitor. Hot-reload supported via profile_engine_reload().
 *
 * profiles.json format:
 *   { "0": { "cpu0_max": "...", "cpu4_max": "...", "cpu7_max": "...",
 *            "cpu0_min": "...", "cpu4_min": "...", "cpu7_min": "...",
 *            "cpu0_gov": "...", "cpu4_gov": "...", "cpu7_gov": "..." }, ... }
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#include "profile_engine.h"
#include "thermal_core.h"
#include "../lib/string_enc.h"

#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <errno.h>

#define TAG "ZProfile"

static int g_default_profile_id = 0;
static package_profile_map_t g_pkg_map[MAX_PACKAGE_MAP];
static int g_pkg_map_count = 0;
static int g_initialized = 0;

/* ---- Minimal JSON value extraction (no malloc) ---- */

/* Find "key": "value" or "key": number in a JSON object string.
 * Writes value into out_val (up to maxlen). Returns 0 on success. */
static int json_get_string(const char *json, const char *key,
                           char *out_val, size_t maxlen)
{
    /* Build search pattern: "key" followed by : then optional ws then " */
    char pattern[128];
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);

    const char *p = strstr(json, pattern);
    if (!p) return -1;
    p += strlen(pattern);

    /* Skip whitespace and colon */
    while (*p && (*p == ' ' || *p == '\t' || *p == '\n' || *p == ':')) p++;

    if (*p == '"') {
        /* Quoted string value */
        p++;
        const char *end = strchr(p, '"');
        if (!end) return -1;
        size_t len = (size_t)(end - p);
        if (len >= maxlen) len = maxlen - 1;
        memcpy(out_val, p, len);
        out_val[len] = '\0';
    } else {
        /* Unquoted number value */
        int i = 0;
        while (*p && *p != ',' && *p != '}' && *p != ' ' && i < (int)maxlen - 1) {
            out_val[i++] = *p++;
        }
        out_val[i] = '\0';
    }
    return 0;
}

/* Find the next "DIGITS": { ... } profile block in JSON.
 * On success, writes the profile ID digits into id_out (up to id_len)
 * and returns pointer to the opening brace content (after '{').
 * On failure, returns NULL. Advances *scan past the block. */
static const char *json_find_next_profile(const char *json, const char **scan,
                                          char *id_out, size_t id_len)
{
    const char *p = *scan;
    while (*p) {
        if (*p == '"') {
            /* Start of potential key */
            const char *key_start = p + 1;
            const char *q = strchr(key_start, '"');
            if (!q) break;
            size_t klen = (size_t)(q - key_start);
            if (klen == 0 || klen >= id_len) { p = q + 1; continue; }

            /* Check all digits */
            int is_num = 1;
            for (size_t i = 0; i < klen; i++) {
                if (key_start[i] < '0' || key_start[i] > '9') { is_num = 0; break; }
            }
            if (!is_num) { p = q + 1; continue; }

            /* Skip colon and whitespace to find '{' */
            const char *r = q + 1;
            while (*r == ' ' || *r == '\t' || *r == '\n' || *r == '\r' || *r == ':') r++;
            if (*r != '{') { p = q + 1; continue; }

            /* Found profile block */
            memcpy(id_out, key_start, klen);
            id_out[klen] = '\0';

            /* Find matching closing brace (simple count) */
            const char *content = r + 1; /* after '{' */
            int depth = 1;
            const char *end = content;
            while (*end && depth > 0) {
                if (*end == '{') depth++;
                else if (*end == '}') depth--;
                if (depth > 0) end++;
            }
            if (depth != 0) { p = q + 1; continue; }

            *scan = end + 1;
            return content;
        }
        p++;
    }
    return NULL;
}

/* ---- Profile loading ---- */

static int load_profiles(const char *path)
{
    FILE *f = fopen(path, "r");
    if (!f) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "Cannot open %s: %s", path, strerror(errno));
        return -1;
    }

    char json_buf[16384];
    size_t n = fread(json_buf, 1, sizeof(json_buf) - 1, f);
    fclose(f);
    if (n == 0) return -1;
    json_buf[n] = '\0';

    int loaded = 0;
    const char *scan = json_buf;

    while (1) {
        char id_str[16] = {0};
        const char *block = json_find_next_profile(json_buf, &scan, id_str, sizeof(id_str));
        if (!block) break;

        /* block points to content after '{', need to find matching '}' */
        /* json_find_next_profile returned content inside braces. We need the closing '}' 
         * position to know block length. Since the function already advances scan,
         * we can compute: the block content is between block and (scan - 1) */
        const char *brace_end = scan - 1; /* points to '}' */

        char block_buf[2048];
        size_t block_len = (size_t)(brace_end - block);
        if (block_len >= sizeof(block_buf)) block_len = sizeof(block_buf) - 1;
        memcpy(block_buf, block, block_len);
        block_buf[block_len] = '\0';

        char val[64];
        thermal_profile_t prof = {0};
        strncpy(prof.id, id_str, MAX_PROFILE_NAME - 1);

        if (json_get_string(block_buf, "cpu0_gov", val, sizeof(val)) == 0) {
            strncpy(prof.governor, val, MAX_GOVERNOR_NAME - 1);
        }
        if (json_get_string(block_buf, "cpu4_gov", val, sizeof(val)) == 0) {
            strncpy(prof.governor4, val, MAX_GOVERNOR_NAME - 1);
        }
        if (json_get_string(block_buf, "cpu7_gov", val, sizeof(val)) == 0) {
            strncpy(prof.governor7, val, MAX_GOVERNOR_NAME - 1);
        }

        uint32_t cpu0_max = 0, cpu4_max = 0, cpu7_max = 0;
        uint32_t cpu0_min = 0, cpu4_min = 0, cpu7_min = 0;

        if (json_get_string(block_buf, "cpu0_max", val, sizeof(val)) == 0)
            cpu0_max = (uint32_t)strtoul(val, NULL, 10);
        if (json_get_string(block_buf, "cpu4_max", val, sizeof(val)) == 0)
            cpu4_max = (uint32_t)strtoul(val, NULL, 10);
        if (json_get_string(block_buf, "cpu7_max", val, sizeof(val)) == 0)
            cpu7_max = (uint32_t)strtoul(val, NULL, 10);
        if (json_get_string(block_buf, "cpu0_min", val, sizeof(val)) == 0)
            cpu0_min = (uint32_t)strtoul(val, NULL, 10);
        if (json_get_string(block_buf, "cpu4_min", val, sizeof(val)) == 0)
            cpu4_min = (uint32_t)strtoul(val, NULL, 10);
        if (json_get_string(block_buf, "cpu7_min", val, sizeof(val)) == 0)
            cpu7_min = (uint32_t)strtoul(val, NULL, 10);

        /* Per-cluster values for thermal_core */
        prof.max_freq_khz = cpu0_max;
        prof.min_freq_khz = cpu0_min;
        prof.max_freq4_khz = cpu4_max;
        prof.max_freq7_khz = cpu7_max;
        prof.min_freq4_khz = cpu4_min;
        prof.min_freq7_khz = cpu7_min;
        prof.active = 0;
        prof.thermal_limit_count = 0;

        thermal_core_register_profile(&prof);
        loaded++;

        __android_log_print(ANDROID_LOG_DEBUG, TAG,
                            "Profile %s: gov=%s max=%u min=%u",
                            prof.id, prof.governor, prof.max_freq_khz, prof.min_freq_khz);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Loaded %d profiles from %s", loaded, path);
    return loaded;
}

/* ---- Public API ---- */

int profile_engine_init(const char *profiles_json_path)
{
    if (g_initialized) return 0;

    g_pkg_map_count = 0;
    memset(g_pkg_map, 0, sizeof(g_pkg_map));

    int n = load_profiles(profiles_json_path);
    if (n < 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Failed to load profiles, using defaults");
    }

    g_default_profile_id = 0;
    g_initialized = 1;
    return 0;
}

int profile_engine_lookup(const char *package_name)
{
    if (!package_name || !package_name[0]) return g_default_profile_id;

    for (int i = 0; i < g_pkg_map_count; i++) {
        if (strcmp(g_pkg_map[i].package, package_name) == 0) {
            return g_pkg_map[i].profile_id;
        }
    }
    return g_default_profile_id;
}

int profile_engine_map_app(const char *package_name, int profile_id)
{
    if (!package_name || !package_name[0]) return -1;

    /* Update existing or add new */
    for (int i = 0; i < g_pkg_map_count; i++) {
        if (strcmp(g_pkg_map[i].package, package_name) == 0) {
            g_pkg_map[i].profile_id = profile_id;
            return 0;
        }
    }

    if (g_pkg_map_count >= MAX_PACKAGE_MAP) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Package map full");
        return -1;
    }

    strncpy(g_pkg_map[g_pkg_map_count].package, package_name,
            sizeof(g_pkg_map[0].package) - 1);
    g_pkg_map[g_pkg_map_count].profile_id = profile_id;
    g_pkg_map_count++;
    return 0;
}

int profile_engine_reload(const char *profiles_json_path)
{
    return load_profiles(profiles_json_path);
}

int profile_engine_get_default(void)
{
    return g_default_profile_id;
}
