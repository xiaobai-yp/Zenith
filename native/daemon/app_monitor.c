/*
 * app_monitor.c — Detects foreground app by scanning /proc/NNN/oom_score_adj.
 *
 * Finds the process with the lowest oom_score_adj that is > 0 (foreground).
 * Falls back to reading /proc/NNN/cmdline for the package name. Maintains a
 * small ring buffer of recent foreground apps.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#include "app_monitor.h"
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>
#include <time.h>
#include <ctype.h>
#include <sys/stat.h>
#include <errno.h>

#define TAG "ZAppMon"

/* Only scan this many PIDs (Android typically has ~300-500) */
#define MAX_PIDS_TO_SCAN 1024

static app_list_t g_recent = {0};
static int g_initialized = 0;

static int64_t monotonic_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Read oom_score_adj for a given PID. Returns value or -1 on error. */
static int read_oom_adj(int pid)
{
    char path[64];
    char buf[32];
    FILE *f;

    snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", pid);
    f = fopen(path, "r");
    if (!f) return -1;

    if (fgets(buf, sizeof(buf), f) == NULL) {
        fclose(f);
        return -1;
    }
    fclose(f);

    return atoi(buf);
}

/* Read cmdline for a PID, extract package name.
 * Android app cmdline looks like: com.example.app
 * Returns 1 on success, 0 on failure. */
static int read_cmdline(int pid, char *out, size_t outlen)
{
    char path[64];
    char buf[MAX_PACKAGE_LEN + 64];
    FILE *f;

    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    f = fopen(path, "r");
    if (!f) return 0;

    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);

    if (n == 0) return 0;
    buf[n] = '\0';

    /* Cmdline args are null-separated; take only the first arg */
    for (size_t i = 0; i < n; i++) {
        if (buf[i] == '\0') { buf[i] = '\0'; break; }
    }

    /* Validate: package names have dots, lowercase letters, maybe underscores */
    if (buf[0] == '\0') return 0;

    /* Copy, ensuring no trailing junk */
    strncpy(out, buf, outlen - 1);
    out[outlen - 1] = '\0';
    return 1;
}

/* Check if PID looks like an app (not a kernel thread, has valid cmdline) */
static int is_app_process(int pid)
{
    char path[64];
    struct stat st;

    /* Kernel threads don't have a real exe */
    snprintf(path, sizeof(path), "/proc/%d/exe", pid);
    if (stat(path, &st) != 0) return 0;

    /* Must have a cmdline */
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    if (access(path, R_OK) != 0) return 0;

    return 1;
}

int app_monitor_init(void)
{
    memset(&g_recent, 0, sizeof(g_recent));
    g_initialized = 1;
    __android_log_print(ANDROID_LOG_INFO, TAG, "App monitor initialized");
    return 0;
}

int app_monitor_detect_fg(foreground_app_t *out)
{
    if (!out) return -1;

    DIR *proc_dir = opendir("/proc");
    if (!proc_dir) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Cannot open /proc");
        return -1;
    }

    int best_oom = INT32_MAX;
    int best_pid = -1;
    char best_cmdline[MAX_PACKAGE_LEN] = {0};
    int scanned = 0;

    struct dirent *ent;
    while ((ent = readdir(proc_dir)) != NULL && scanned < MAX_PIDS_TO_SCAN) {
        /* Only numeric entries (PIDs) */
        if (ent->d_name[0] < '0' || ent->d_name[0] > '9') continue;

        int pid = atoi(ent->d_name);
        if (pid <= 0) continue;
        scanned++;

        int oom = read_oom_adj(pid);
        if (oom < 0) continue;

        /* Foreground apps have oom_adj between 0 and ~700 (FOREGROUND_APP_ADJ = 0,
         * but some vendors use positive values). We want the LOWEST positive oom_adj.
         * Skip 0 (system) and negative (protected). */
        if (oom <= 0 || oom >= 1000) continue;

        /* Prefer lower oom_adj = more foreground */
        if (oom < best_oom) {
            if (is_app_process(pid)) {
                char cmdline[MAX_PACKAGE_LEN];
                if (read_cmdline(pid, cmdline, sizeof(cmdline))) {
                    best_oom = oom;
                    best_pid = pid;
                    strncpy(best_cmdline, cmdline, sizeof(best_cmdline) - 1);
                }
            }
        }
    }
    closedir(proc_dir);

    if (best_pid < 0) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
                            "No foreground app detected (scanned %d PIDs)", scanned);
        return -1;
    }

    /* Fill output */
    strncpy(out->package, best_cmdline, MAX_PACKAGE_LEN - 1);
    out->package[MAX_PACKAGE_LEN - 1] = '\0';
    out->pid = best_pid;
    out->oom_adj = best_oom;

    /* Update recent list (ring buffer) */
    int64_t now = monotonic_ms();

    /* Check if this app is already in the recent list */
    int found = 0;
    for (int i = 0; i < g_recent.count; i++) {
        if (strcmp(g_recent.apps[i].package, out->package) == 0) {
            g_recent.apps[i].pid = best_pid;
            g_recent.apps[i].oom_adj = best_oom;
            found = 1;
            break;
        }
    }

    if (!found) {
        int idx = g_recent.count;
        if (idx >= MAX_FOREGROUND_APPS) {
            /* Shift out oldest */
            memmove(&g_recent.apps[0], &g_recent.apps[1],
                    sizeof(foreground_app_t) * (MAX_FOREGROUND_APPS - 1));
            idx = MAX_FOREGROUND_APPS - 1;
        } else {
            g_recent.count++;
        }
        g_recent.apps[idx] = *out;
    }

    g_recent.timestamp_ms = now;

    __android_log_print(ANDROID_LOG_DEBUG, TAG,
                        "FG app: %s (pid=%d oom=%d)",
                        out->package, out->pid, out->oom_adj);
    return 0;
}

int app_monitor_get_list(app_list_t *out)
{
    if (!out) return -1;
    *out = g_recent;
    return 0;
}
