/*
 * battery_monitor.c — Battery drain rate calculation.
 *
 * Maintains a circular buffer of battery samples and computes drain rates
 * (percent/hour) from capacity deltas over time. Splits drain into screen-on
 * and screen-off contributions. Uses fixed-size arrays — no malloc.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#include "battery_monitor.h"
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define TAG "ZBattery"

#define SAMPLE_BUF_SIZE  16
#define MIN_DRAIN_INTERVAL_MS  60000  /* 1 minute minimum between samples */

static battery_stats_t g_stats = {0};
static int g_screen_on = 1;
static int g_initialized = 0;

/* Running accumulators for screen on/off drain */
static double g_screen_on_delta_pct = 0.0;
static double g_screen_on_delta_ms = 0.0;
static double g_screen_off_delta_pct = 0.0;
static double g_screen_off_delta_ms = 0.0;

int battery_monitor_init(void)
{
    memset(&g_stats, 0, sizeof(g_stats));
    g_stats.sample_idx = 0;
    g_stats.sample_count = 0;
    g_screen_on = 1;
    g_screen_on_delta_pct = 0.0;
    g_screen_on_delta_ms = 0.0;
    g_screen_off_delta_pct = 0.0;
    g_screen_off_delta_ms = 0.0;
    g_initialized = 1;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Battery monitor initialized");
    return 0;
}

int battery_monitor_update(int capacity_pct, int current_now_ua,
                           int voltage_uv, int online,
                           int64_t timestamp_ms)
{
    if (!g_initialized) return -1;

    /* Sanity check */
    if (capacity_pct < 0 || capacity_pct > 100) return -1;

    battery_sample_t *prev = NULL;
    battery_sample_t *cur = NULL;

    /* Store new sample in circular buffer */
    int idx = g_stats.sample_idx;
    cur = &g_stats.samples[idx];

    /* Get previous sample if we have one */
    if (g_stats.sample_count > 0) {
        int prev_idx = (idx - 1 + SAMPLE_BUF_SIZE) % SAMPLE_BUF_SIZE;
        prev = &g_stats.samples[prev_idx];
    }

    cur->capacity_pct = capacity_pct;
    cur->current_now_ua = current_now_ua;
    cur->voltage_uv = voltage_uv;
    cur->online = online;
    cur->timestamp_ms = timestamp_ms;

    g_stats.sample_idx = (idx + 1) % SAMPLE_BUF_SIZE;
    if (g_stats.sample_count < SAMPLE_BUF_SIZE)
        g_stats.sample_count++;

    /* Update current stats */
    g_stats.capacity_pct = capacity_pct;
    g_stats.current_now_ua = current_now_ua;
    g_stats.online = online;

    /* Calculate drain rate from previous sample */
    if (prev && timestamp_ms > prev->timestamp_ms) {
        double dt_ms = (double)(timestamp_ms - prev->timestamp_ms);
        double dt_hours = dt_ms / 3600000.0;

        if (dt_hours < 0.0001) return 0; /* too small to measure */

        int dc = prev->capacity_pct - capacity_pct;
        if (dc > 0 && !online) {
            /* Discharging: positive drain */
            double drain_rate = (double)dc / dt_hours;

            /* Weighted exponential moving average */
            if (g_stats.drain_pct_per_hour < 0.001) {
                g_stats.drain_pct_per_hour = (float)drain_rate;
            } else {
                g_stats.drain_pct_per_hour =
                    (float)(g_stats.drain_pct_per_hour * 0.7 + drain_rate * 0.3);
            }

            /* Split screen on/off */
            if (g_screen_on) {
                g_screen_on_delta_pct += (double)dc;
                g_screen_on_delta_ms += dt_ms;
            } else {
                g_screen_off_delta_pct += (double)dc;
                g_screen_off_delta_ms += dt_ms;
            }

            /* Compute screen on/off rates */
            if (g_screen_on_delta_ms > 60000.0) {
                g_stats.screen_on_drain =
                    (float)(g_screen_on_delta_pct / (g_screen_on_delta_ms / 3600000.0));
            }
            if (g_screen_off_delta_ms > 60000.0) {
                g_stats.screen_off_drain =
                    (float)(g_screen_off_delta_pct / (g_screen_off_delta_ms / 3600000.0));
            }

            /* Idle drain (low current_now while discharging) */
            if (abs(current_now_ua) < 100000) { /* < 100mA */
                g_stats.idle_drain_pct_per_hr = g_stats.drain_pct_per_hour * 0.5f;
            }

            __android_log_print(ANDROID_LOG_DEBUG, TAG,
                                "Drain: %.1f%%/hr (cap=%d%%)",
                                drain_rate, capacity_pct);
        } else if (dc < 0) {
            /* Charging or capacity reported increase */
            g_stats.drain_pct_per_hour = 0.0f;
        }
    }

    return 0;
}

const battery_stats_t *battery_monitor_get_stats(void)
{
    return &g_stats;
}

void battery_monitor_set_screen_on(int is_on)
{
    g_screen_on = is_on ? 1 : 0;
}
