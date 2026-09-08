/*
 * benchmark.h — Header for benchmark recording during thermal sessions.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_BENCHMARK_H
#define ZENITH_BENCHMARK_H

#include <stdint.h>
#include <stddef.h>

#define BENCHMARK_MAX_POINTS 300  /* last 300 seconds at 1Hz */

typedef struct {
    int64_t ts;        /* monotonic ms */
    int     fps;
    int     temp;      /* millidegrees Celsius */
    int     batt_pct;  /* battery capacity 0-100 */
    int     current_ma; /* current in milliamps (abs value) */
} benchmark_point_t;

/* Start / stop a benchmark session */
int  benchmark_start(void);
int  benchmark_stop(void);

/* Record one data point (call once per second during session).
 * Reads current temp/battery/fps from live state. */
void benchmark_record(void);

/* Check if a session is active */
int benchmark_is_active(void);

/* Elapsed time of current session in milliseconds */
int64_t benchmark_elapsed_ms(void);

/*
 * Export recorded points as JSON array into `out`.
 * Format: [{"ts":..., "fps":..., "temp":..., "batt_pct":..., "current_ma":...}, ...]
 * Returns 0 on success, -1 if buffer too small.
 */
int benchmark_export_json(char *out, size_t len);

#endif /* ZENITH_BENCHMARK_H */
