/*
 * app_monitor.h — Header for foreground app detection.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_APP_MONITOR_H
#define ZENITH_APP_MONITOR_H

#include <stdint.h>

#define MAX_PACKAGE_LEN 256
#define MAX_FOREGROUND_APPS 8

typedef struct {
    char package[MAX_PACKAGE_LEN];  /* null-terminated package name */
    int  pid;
    int  oom_adj;
} foreground_app_t;

typedef struct {
    foreground_app_t apps[MAX_FOREGROUND_APPS];
    int              count;
    int64_t          timestamp_ms;
} app_list_t;

/* Initialize app monitor */
int app_monitor_init(void);

/* Detect current foreground app (most recent / lowest oom_adj > 0) */
int app_monitor_detect_fg(foreground_app_t *out);

/* Get list of recent foreground apps */
int app_monitor_get_list(app_list_t *out);

#endif /* ZENITH_APP_MONITOR_H */
