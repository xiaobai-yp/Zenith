/*
 * profile_engine.h — Loads profiles.json, maps package→profile_id, hot-reload.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_PROFILE_ENGINE_H
#define ZENITH_PROFILE_ENGINE_H

#include "thermal_core.h"
#include <stdint.h>

#define MAX_PACKAGE_MAP  64

typedef struct {
    char package[256];
    int  profile_id;  /* index into thermal_core profiles, -1 = none */
} package_profile_map_t;

/* Initialize: load profiles.json, register all profiles with thermal_core */
int profile_engine_init(const char *profiles_json_path);

/* Map a package name to a profile_id. Returns profile_id or -1 if not found. */
int profile_engine_lookup(const char *package_name);

/* Register an app→profile mapping (from IPC or UI) */
int profile_engine_map_app(const char *package_name, int profile_id);

/* Hot-reload: re-read profiles.json */
int profile_engine_reload(const char *profiles_json_path);

/* Get default profile ID */
int profile_engine_get_default(void);

#endif /* ZENITH_PROFILE_ENGINE_H */
