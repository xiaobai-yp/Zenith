/*
 * thermal_core.h — Header for thermal profile application and governor control.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_THERMAL_CORE_H
#define ZENITH_THERMAL_CORE_H

#include <stdint.h>
#include "sysfs_monitor.h"

#define MAX_PROFILES        32
#define MAX_PROFILE_NAME    32
#define MAX_GOVERNOR_NAME   32

typedef struct {
    char        id[MAX_PROFILE_NAME];     /* e.g. "gaming", "balanced" */
    int         active;
    char        governor[MAX_GOVERNOR_NAME]; /* "performance", "powersave", etc. */
    char        governor4[MAX_GOVERNOR_NAME]; /* per-cluster: policy4 governor */
    char        governor7[MAX_GOVERNOR_NAME]; /* per-cluster: policy7 governor */
    uint32_t    max_freq_khz;             /* 0 = no limit */
    uint32_t    min_freq_khz;             /* 0 = no limit */
    uint32_t    max_freq4_khz;            /* per-cluster: policy4 max */
    uint32_t    max_freq7_khz;            /* per-cluster: policy7 max */
    uint32_t    min_freq4_khz;            /* per-cluster: policy4 min */
    uint32_t    min_freq7_khz;            /* per-cluster: policy7 min */
    int         thermal_limit_milli[MAX_THERMAL_ZONES]; /* per-zone limit, 0=disabled */
    int         thermal_limit_count;
} thermal_profile_t;

/* Initialize thermal core: discover writable sysfs nodes */
int thermal_core_init(void);

/* Apply a named profile. Returns 0 on success, -1 if not found. */
int thermal_core_apply_profile(const char *profile_id);

/* Set thermal limit for a specific zone (millidegrees). 0 disables. */
int thermal_core_set_limit(int zone_id, int temp_milli);

/* Set governor for a specific core (-1 = all cores) */
int thermal_core_set_governor(int core_id, const char *governor);

/* Set max frequency for a specific core (-1 = all cores) */
int thermal_core_set_max_freq(int core_id, uint32_t freq_khz);

/* Get current active profile id, or NULL */
const char *thermal_core_get_active_profile(void);

/* Register a profile (for profile_engine to call) */
int thermal_core_register_profile(const thermal_profile_t *profile);

#endif /* ZENITH_THERMAL_CORE_H */
