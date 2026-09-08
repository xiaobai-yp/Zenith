/*
 * fps_monitor.h — Header for FPS monitoring via sysfs.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_FPS_MONITOR_H
#define ZENITH_FPS_MONITOR_H

#include <stdint.h>
#include <stddef.h>

/* EMA window sizes (frames) */
#define FPS_SHORT_WINDOW   30   /* ~0.5s at 60fps */
#define FPS_LONG_WINDOW   180   /* ~3s at 60fps  */

/* Initialize: detect sysfs FPS path */
int fps_monitor_init(void);

/*
 * Read FPS and return smoothed values.
 * short_fps / long_fps: dual EMA smoothed FPS (frames/second).
 * Returns 0 on success, -1 if no sysfs path available (fake 60fps).
 */
int fps_monitor_read(int *short_fps, int *long_fps);

/*
 * Get session statistics.
 * avg_fps: average of all raw readings this session.
 * min_fps / max_fps: min and max raw readings this session.
 * Returns 0 if at least one sample exists, -1 otherwise.
 */
int fps_monitor_get_avg(int *avg_fps, int *min_fps, int *max_fps);

/* Reset session statistics and EMA state */
void fps_monitor_reset_session(void);

#endif /* ZENITH_FPS_MONITOR_H */
