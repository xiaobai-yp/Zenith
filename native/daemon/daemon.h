#ifndef ZENITH_DAEMON_H
#define ZENITH_DAEMON_H

#include <stdint.h>
#include <sys/types.h>

/* ---- Profiles ---- */
typedef enum {
    PROFILE_NONE = 0,
    PROFILE_BALANCED,
    PROFILE_GAMING,
    PROFILE_POWERSAVE,
    PROFILE_BATTERY_SAVER,
    PROFILE_COUNT
} profile_id_t;

typedef struct {
    const char *name;        /* "balanced", "game", etc */
    const char *governor;    /* schedutil, performance, powersave */
    uint32_t    max_freq_khz; /* scaling_max_freq */
    uint32_t    min_freq_khz; /* scaling_min_freq */
    int         thermal_limit_c; /* max temp before throttle */
} profile_t;

extern const profile_t g_profiles[PROFILE_COUNT];

/* ---- Sysfs paths (encrypted at build time) ---- */
#define SYS_CPU_DIR   "/sys/devices/system/cpu"
#define SYS_THERMAL   "/sys/class/thermal"

/* ---- IPC protocol ---- */
#define ZENITH_SOCKET  "/dev/socket/zenithd"
#define ZENITH_SOCKET_PERM 0660

typedef enum {
    MSG_GET_STATUS = 1,
    MSG_SET_PROFILE,
    MSG_GET_APPS,
    MSG_SET_THERMAL,
    MSG_HEARTBEAT,
    MSG_VERIFY,
} msg_type_t;

/* status payload returned by daemon on MSG_GET_STATUS */
typedef struct {
    int  temperature_c;      /* skin/highest thermal zone */
    int  battery_pct;
    int  battery_current_ma;
    const char *governor;    /* current governor */
    uint32_t max_freq_khz;
    profile_id_t active_profile;
} status_t;

#endif
