/*
 * zenithd.c — Main entry point for ZenithThermal daemon.
 *
 * Daemon lifecycle:
 *   1. Parse args, daemonize (double fork)
 *   2. Set signal handlers (SIGTERM, SIGINT, SIGHUP for reload)
 *   3. Check for debugger
 *   4. Verify ROM signature
 *   5. Init subsystems: sysfs_monitor, thermal_core, battery_monitor,
 *      app_monitor, profile_engine, socket_server
 *   6. Enter main loop: poll socket + periodic sysfs read + app detect
 *   7. Clean shutdown on signal
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#include "daemon.h"
#include "sysfs_monitor.h"
#include "thermal_core.h"
#include "battery_monitor.h"
#include "app_monitor.h"
#include "socket_server.h"
#include "crypto.h"
#include "profile_engine.h"
#include "../lib/string_enc.h"

#include <android/log.h>
#ifndef ZENITH_ROM_BUILD
/* NDK/standalone build: no libcutils. Use __system_property_set from libc directly. */
#include <sys/system_properties.h>
#else
#include <cutils/properties.h>
#endif
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>
#include <fcntl.h>
#include <time.h>

#define TAG "ZenithD"

/* ---- Configuration ---- */

#define PROFILE_JSON_PATH        "/vendor/etc/profiles.json"
#define PROFILE_JSON_FALLBACK    "/data/local/zenith/profiles.json"
#define MONITOR_INTERVAL_MS      5000   /* 5s between sysfs reads */
#define APP_DETECT_INTERVAL_MS   2000   /* 2s between app scans */
#define PID_FILE                 "/data/local/zenith/zenithd.pid"

/* ---- Global state ---- */

static volatile sig_atomic_t g_running = 1;
static volatile sig_atomic_t g_reload  = 0;

/* ---- Signal handlers ---- */

static void sigterm_handler(int sig)
{
    (void)sig;
    g_running = 0;
}

static void sighup_handler(int sig)
{
    (void)sig;
    g_reload = 1;
}

static void setup_signals(void)
{
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));

    sa.sa_handler = sigterm_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);

    sa.sa_handler = sighup_handler;
    sigaction(SIGHUP, &sa, NULL);

    /* Ignore SIGPIPE (broken socket writes) */
    { struct sigaction sa = {0}; sa.sa_handler = SIG_IGN; sigaction(SIGPIPE, &sa, NULL); }
}

/* ---- Daemonization ---- */

static int daemonize(void)
{
    pid_t pid = fork();
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "First fork failed: %s", strerror(errno));
        return -1;
    }
    if (pid > 0) {
        /* Parent exits */
        _exit(0);
    }

    /* Child becomes session leader */
    if (setsid() < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "setsid failed: %s", strerror(errno));
        return -1;
    }

    /* Second fork to prevent reacquiring a terminal */
    pid = fork();
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "Second fork failed: %s", strerror(errno));
        return -1;
    }
    if (pid > 0) {
        _exit(0);
    }

    /* Redirect stdio to /dev/null */
    int fd = open("/dev/null", O_RDWR);
    if (fd >= 0) {
        dup2(fd, STDIN_FILENO);
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) close(fd);
    }

    return 0;
}

/* ---- PID file ---- */

static void write_pid_file(void)
{
    /* Ensure parent directory exists */
    mkdir("/data/local/zenith", 0755);

    FILE *f = fopen(PID_FILE, "w");
    if (f) {
        fprintf(f, "%d\n", (int)getpid());
        fclose(f);
    }
}

static void remove_pid_file(void)
{
    unlink(PID_FILE);
}

/* ---- Hot-reload ---- */

static void do_reload(const char *profiles_path)
{
    __android_log_print(ANDROID_LOG_INFO, TAG, "Reloading profiles...");
    profile_engine_reload(profiles_path);
    g_reload = 0;
}

/* ---- Main ---- */

static int64_t monotonic_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}


/* Try to set persist.sys.zenith.thermal property.
 * Returns 0 on success. Falls back to direct sysfs if property_set unavailable. */
static int set_thermal_property(int profile_id)
{
#ifndef ZENITH_ROM_BUILD
    /*
     * NDK/standalone build: libcutils unavailable, so no init.rc trigger path.
     * Always fall through so callers apply profiles directly to sysfs.
     */
    (void)profile_id;
    __android_log_print(ANDROID_LOG_DEBUG, TAG,
                        "NDK build: applying profile %d directly to sysfs", profile_id);
    return -1;
#else
    char value[PROPERTY_VALUE_MAX];
    snprintf(value, sizeof(value), "%d", profile_id);

    int ret = property_set("persist.sys.zenith.thermal", value);
    if (ret == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
                            "Set persist.sys.zenith.thermal=%d via property", profile_id);
        return 0;
    }
    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "property_set failed (ret=%d), falling back to direct sysfs", ret);
    return -1;
#endif
}
int main(int argc, char *argv[])
{
    int foreground = 0;

    /* Parse args */
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-f") == 0 || strcmp(argv[i], "--foreground") == 0) {
            foreground = 1;
        } else if (strcmp(argv[i], "-v") == 0 || strcmp(argv[i], "--version") == 0) {
            printf("zenithd v1.0.0\n");
            return 0;
        }
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "ZenithThermal daemon starting (pid=%d)", getpid());

    /* Check for debugger before doing anything sensitive */
    if (crypto_check_debugger()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "Debugger detected, aborting");
        return 1;
    }

    /* Daemonize unless running in foreground */
    if (!foreground) {
        if (daemonize() < 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Daemonize failed");
            return 1;
        }
    }

    /* Setup signal handlers */
    setup_signals();
    write_pid_file();

    __android_log_print(ANDROID_LOG_INFO, TAG, "Daemon running (pid=%d)", getpid());

    /* Init subsystems */
    sysfs_monitor_init();
    thermal_core_init();
    battery_monitor_init();
    app_monitor_init();

    /* Load profiles — try system path first, then fallback */
    const char *profiles_path = PROFILE_JSON_PATH;
    {
        FILE *f = fopen(PROFILE_JSON_PATH, "r");
        if (f) {
            fclose(f);
        } else {
            profiles_path = PROFILE_JSON_FALLBACK;
        }
    }
    profile_engine_init(profiles_path);

    /* Init socket server */
    if (socket_server_init() < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "Socket server init failed, continuing without IPC");
    }

    /* ---- Main monitoring loop ---- */
    int64_t last_sysfs_read = 0;
    int64_t last_app_detect = 0;

    while (g_running) {
        int64_t now = monotonic_ms();

        /* Handle SIGHUP reload */
        if (g_reload) {
            do_reload(profiles_path);
        }

        /* Periodic sysfs read + battery update */
        if (now - last_sysfs_read >= MONITOR_INTERVAL_MS) {
            sysfs_snapshot_t snap;
            if (sysfs_monitor_read(&snap) == 0) {
                battery_monitor_update(
                    snap.battery.capacity_pct,
                    snap.battery.current_now_ua,
                    snap.battery.voltage_now_uv,
                    snap.battery.online,
                    snap.timestamp_ms);
            }
            last_sysfs_read = now;
        }

        /* Periodic app detection + profile switch */
        if (now - last_app_detect >= APP_DETECT_INTERVAL_MS) {
            foreground_app_t fg;
            if (app_monitor_detect_fg(&fg) == 0) {
                int profile_id = profile_engine_lookup(fg.package);
                char profile_id_str[16];
                snprintf(profile_id_str, sizeof(profile_id_str), "%d", profile_id);

                /* Priority: property trigger (init.rc) first, direct sysfs fallback */
                if (set_thermal_property(profile_id) != 0) {
                    /* init.rc missing or property set failed — apply directly */
                    thermal_core_apply_profile(profile_id_str);
                    __android_log_print(ANDROID_LOG_DEBUG, TAG,
                                        "Applied profile %d directly (no init.rc)", profile_id);
                }

                __android_log_print(ANDROID_LOG_DEBUG, TAG,
                                    "App: %s -> profile %d",
                                    fg.package, profile_id);
            }
            last_app_detect = now;
        }

        /* Process socket events (non-blocking, 200ms timeout) */
        socket_server_poll(200);

        /* Brief sleep to avoid busy-wait */
        usleep(50000); /* 50ms */
    }

    /* ---- Clean shutdown ---- */
    __android_log_print(ANDROID_LOG_INFO, TAG, "Shutting down...");

    socket_server_stop();
    remove_pid_file();

    __android_log_print(ANDROID_LOG_INFO, TAG, "Daemon stopped");
    return 0;
}
