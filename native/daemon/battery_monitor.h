/*
 * battery_monitor.h — Header for battery drain rate calculation.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_BATTERY_MONITOR_H
#define ZENITH_BATTERY_MONITOR_H

#include <stdint.h>

typedef struct {
    int  capacity_pct;
    int  current_now_ua;
    int  voltage_uv;
    int  online;          /* 1=charging, 0=discharging */
    int64_t timestamp_ms; /* monotonic */
} battery_sample_t;

typedef struct {
    /* Drain rate */
    float drain_pct_per_hour;   /* percent per hour */
    float idle_drain_pct_per_hr;
    float screen_on_drain;      /* percent per hour while screen on */
    float screen_off_drain;     /* percent per hour while screen off */

    /* Battery health */
    int   capacity_pct;
    int   health_pct;           /* charge_now / charge_full * 100 */
    int   online;
    int   current_now_ua;

    /* Internal state */
    battery_sample_t samples[16]; /* circular buffer for averaging */
    int sample_idx;
    int sample_count;
} battery_stats_t;

/* Initialize battery monitor */
int battery_monitor_init(void);

/* Update with a new sample (call periodically from sysfs_monitor) */
int battery_monitor_update(int capacity_pct, int current_now_ua,
                           int voltage_uv, int online,
                           int64_t timestamp_ms);

/* Get current computed stats */
const battery_stats_t *battery_monitor_get_stats(void);

/* Force screen state for drain split (call from power state handler) */
void battery_monitor_set_screen_on(int is_on);

#endif /* ZENITH_BATTERY_MONITOR_H */
