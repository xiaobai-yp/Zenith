/*
 * benchmark.c — Benchmark recording during thermal sessions.
 *
 * Records FPS, temperature, battery, and current draw at 1Hz into a
 * circular buffer. Exports as JSON for external analysis.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#include "benchmark.h"
#include "fps_monitor.h"
#include "sysfs_monitor.h"

#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#define TAG "ZThermal"

/* ---- Internal state ---- */

static benchmark_point_t g_ring[BENCHMARK_MAX_POINTS];
static int               g_ring_head;   /* next write index */
static int               g_ring_count;  /* number of valid entries */
static int               g_active;
static int64_t           g_start_ms;

/* ---- Helpers ---- */

static int64_t monotonic_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ---- Public API ---- */

int benchmark_start(void)
{
    if (g_active) return -1;  /* already running */
    g_ring_head  = 0;
    g_ring_count = 0;
    g_start_ms   = monotonic_ms();
    g_active     = 1;
    fps_monitor_reset_session();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Benchmark: session started");
    return 0;
}

int benchmark_stop(void)
{
    if (!g_active) return -1;
    g_active = 0;
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "Benchmark: session stopped, %d points recorded",
                        g_ring_count);
    return 0;
}

int benchmark_is_active(void)
{
    return g_active;
}

int64_t benchmark_elapsed_ms(void)
{
    if (!g_active) return 0;
    return monotonic_ms() - g_start_ms;
}

void benchmark_record(void)
{
    if (!g_active) return;

    sysfs_snapshot_t snap;
    int short_fps, long_fps;

    /* Read live values */
    sysfs_monitor_read(&snap);
    fps_monitor_read(&short_fps, &long_fps);

    /* Use short EMA for instantaneous, long EMA available via fps_monitor_read */
    benchmark_point_t pt;
    pt.ts         = snap.timestamp_ms;
    pt.fps        = short_fps;
    pt.temp       = (snap.zone_count > 0) ? snap.zones[0].temp_milli : 0;
    pt.batt_pct   = snap.battery.capacity_pct;
    /* current_now_ua is microamps; convert to milliamps (absolute) */
    pt.current_ma = (snap.battery.current_now_ua < 0)
                    ? (-snap.battery.current_now_ua / 1000)
                    : (snap.battery.current_now_ua / 1000);

    /* Write into circular buffer */
    g_ring[g_ring_head] = pt;
    g_ring_head = (g_ring_head + 1) % BENCHMARK_MAX_POINTS;
    if (g_ring_count < BENCHMARK_MAX_POINTS)
        g_ring_count++;
}

int benchmark_export_json(char *out, size_t len)
{
    if (!out || len < 3) return -1;

    size_t pos = 0;
    out[pos++] = '[';

    for (int i = 0; i < g_ring_count && pos < len - 1; i++) {
        int idx = (g_ring_count < BENCHMARK_MAX_POINTS)
                  ? i
                  : (g_ring_head + i) % BENCHMARK_MAX_POINTS;
        const benchmark_point_t *p = &g_ring[idx];

        int n = snprintf(out + pos, len - pos,
                         "%s{\"ts\":%lld,\"fps\":%d,\"temp\":%d,"
                         "\"batt_pct\":%d,\"current_ma\":%d}",
                         (i > 0) ? "," : "",
                         (long long)p->ts, p->fps, p->temp,
                         p->batt_pct, p->current_ma);
        if (n < 0) return -1;
        pos += (size_t)n;
    }

    if (pos >= len - 1) return -1;  /* truncated */
    out[pos++] = ']';
    out[pos]   = '\0';
    return 0;
}

/* Return up to n most recent points (chronological order). */
int benchmark_get_last_points(int n, benchmark_point_t *out)
{
    if (n <= 0 || !out) return 0;
    if (n > BENCHMARK_MAX_POINTS) n = BENCHMARK_MAX_POINTS;
    if (g_ring_count == 0) return 0;

    int take = (g_ring_count < n) ? g_ring_count : n;
    int start_idx = (g_ring_count < BENCHMARK_MAX_POINTS) ? 0 :
                    (g_ring_head - take + BENCHMARK_MAX_POINTS) % BENCHMARK_MAX_POINTS;
    for (int i = 0; i < take; i++) {
        int idx = (start_idx + i) % BENCHMARK_MAX_POINTS;
        out[i] = g_ring[idx];
    }
    return take;
}
