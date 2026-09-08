#ifndef ZENITH_DAEMON_H
#define ZENITH_DAEMON_H

#include <stdint.h>
#include <sys/types.h>

typedef enum {
    PROFILE_NONE = 0,
    PROFILE_BALANCED,
    PROFILE_GAMING,
    PROFILE_POWERSAVE,
    PROFILE_BATTERY_SAVER,
    PROFILE_COUNT
} profile_id_t;

typedef struct {
    const char *name;
    const char *governor;
    uint32_t    max_freq_khz;
    uint32_t    min_freq_khz;
    int         thermal_limit_c;
} profile_t;

extern const profile_t g_profiles[PROFILE_COUNT];

#define SYS_CPU_DIR   "/sys/devices/system/cpu"
#define SYS_THERMAL   "/sys/class/thermal"
#define ZENITH_SOCKET "/dev/socket/zenithd"

typedef struct {
    int  temperature_c;
    int  battery_pct;
    int  battery_current_ma;
    const char *governor;
    uint32_t max_freq_khz;
    profile_id_t active_profile;
} status_t;

#endif
