/*
 * sysfs_monitor.h — Header for sysfs thermal/CPU/battery monitoring.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_SYSFS_MONITOR_H
#define ZENITH_SYSFS_MONITOR_H

#include <stdint.h>
#include <stddef.h>

#define MAX_CORES           8
#define MAX_THERMAL_ZONES   16
#define MAX_PATH_LEN        256
#define MAX_LINE_LEN        512

typedef struct {
    int  zone_id;
    char type[64];        /* e.g. "cpu-0-0-usr" */
    int  temp_milli;      /* millidegrees Celsius */
    int  trip_point;      /* current trip point temp */
    int  mode;            /* 0=enabled, 1=disabled */
} thermal_zone_t;

typedef struct {
    int online;           /* 1 if charging */
    int capacity_pct;     /* 0-100 */
    int current_now_ua;   /* microamps, negative=discharge */
    int voltage_now_uv;   /* microvolts */
    int temp_deci;        /* decidegrees Celsius */
    int charge_full_ua;   /* microamps (full capacity) */
    int charge_now_ua;    /* microamps (current charge) */
} battery_info_t;

typedef struct {
    char governor[64];
    uint32_t freq_khz;    /* current frequency in KHz */
    uint32_t max_freq_khz;
    uint32_t min_freq_khz;
    uint32_t scaling_max_khz;
} cpu_core_info_t;

typedef struct {
    thermal_zone_t   zones[MAX_THERMAL_ZONES];
    int              zone_count;
    battery_info_t   battery;
    cpu_core_info_t  cores[MAX_CORES];
    int              core_count;
    int64_t          timestamp_ms; /* monotonic ms */
} sysfs_snapshot_t;

/* Initialize: discover thermal zones, cores, battery paths */
int sysfs_monitor_init(void);

/* Take a snapshot: read all current values into `snap` */
int sysfs_monitor_read(sysfs_snapshot_t *snap);

/* Read a single sysfs file, trim newline, return length or -1 */
int sysfs_read_file(const char *path, char *buf, size_t buflen);

#endif /* ZENITH_SYSFS_MONITOR_H */
