/*
 * fps_monitor.c — FPS monitoring via sysfs.
 *
 * Reads GPU/display FPS from vendor sysfs nodes. Falls back to fake
 * 60fps placeholder when no sysfs path is available.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#include "fps_monitor.h"
#include "sysfs_monitor.h"

#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#define TAG "ZThermal"

/* ---- Internal state ---- */

static char g_fps_path[MAX_PATH_LEN] = {0};
static int  g_has_sysfs = 0;
static int  g_fake_mode  = 0;

/* EMA state */
static double g_ema_short;
static double g_ema_long;
static int    g_ema_initialized;

/* Session stats */
static int64_t g_session_sum;
static int     g_session_count;
static int     g_session_min;
static int     g_session_max;
static int     g_session_has_samples;

/* ---- Helpers ---- */

static int read_fps_raw(void)
{
    char buf[64];
    if (!g_has_sysfs) {
        /* ponytail: fake 60fps placeholder for devices with no sysfs FPS node.
         * Upgrade path: surfaceflinger framestats, /proc/gpu_stats, or Perfetto. */
        return 60;
    }
    int n = sysfs_read_file(g_fps_path, buf, sizeof(buf));
    if (n <= 0) return 60;
    return atoi(buf);
}

static double ema_alpha(int window)
{
    return 2.0 / ((double)window + 1.0);
}

/* ---- Init ---- */

int fps_monitor_init(void)
{
    static const char *paths[] = {
        "/sys/class/drm/sde-crtc-0/measured_fps",
        "/sys/class/graphics/fb0/fps",
        "/sys/class/misc/mali0/device/fps",
        "/sys/kernel/debug/mali/fps",
        "/sys/class/drm/card0/sde-crtc-0/measured_fps",
    };

    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        char buf[64];
        int n = sysfs_read_file(paths[i], buf, sizeof(buf));
        if (n > 0 && atoi(buf) > 0) {
            strncpy(g_fps_path, paths[i], MAX_PATH_LEN - 1);
            g_has_sysfs = 1;
            __android_log_print(ANDROID_LOG_INFO, TAG, "FPS: using %s", g_fps_path);
            break;
        }
    }

    if (!g_has_sysfs) {
        g_fake_mode = 1;
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "FPS: no sysfs FPS node found, using fake 60fps");
    }

    fps_monitor_reset_session();
    return 0;
}

/* ---- Read ---- */

int fps_monitor_read(int *short_fps, int *long_fps)
{
    int raw = read_fps_raw();

    /* Update session stats */
    g_session_sum += raw;
    g_session_count++;
    if (!g_session_has_samples) {
        g_session_min = raw;
        g_session_max = raw;
        g_session_has_samples = 1;
    } else {
        if (raw < g_session_min) g_session_min = raw;
        if (raw > g_session_max) g_session_max = raw;
    }

    /* Update EMA */
    if (!g_ema_initialized) {
        g_ema_short = (double)raw;
        g_ema_long  = (double)raw;
        g_ema_initialized = 1;
    } else {
        double as = ema_alpha(FPS_SHORT_WINDOW);
        double al = ema_alpha(FPS_LONG_WINDOW);
        g_ema_short = as * raw + (1.0 - as) * g_ema_short;
        g_ema_long  = al * raw + (1.0 - al) * g_ema_long;
    }

    if (short_fps) *short_fps = (int)(g_ema_short + 0.5);
    if (long_fps)  *long_fps  = (int)(g_ema_long  + 0.5);
    return 0;
}

/* ---- Session stats ---- */

int fps_monitor_get_avg(int *avg_fps, int *min_fps, int *max_fps)
{
    if (!g_session_has_samples) return -1;
    if (avg_fps) *avg_fps = (int)(g_session_sum / g_session_count);
    if (min_fps) *min_fps = g_session_min;
    if (max_fps) *max_fps = g_session_max;
    return 0;
}

void fps_monitor_reset_session(void)
{
    g_session_sum = 0;
    g_session_count = 0;
    g_session_min = 0;
    g_session_max = 0;
    g_session_has_samples = 0;
    g_ema_short = 0.0;
    g_ema_long  = 0.0;
    g_ema_initialized = 0;
}
