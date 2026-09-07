/*
 * ipc_protocol.h — IPC message structs for socket communication between
 * ZenithThermal daemon (zenithd) and the Android Kotlin UI app.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_IPC_PROTOCOL_H
#define ZENITH_IPC_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>

/* ---- Constants ---- */

#define ZENITH_MSG_VERSION       1
#define ZENITH_MAX_PAYLOAD       4096
#define ZENITH_MAX_PKG_NAME      256
#define ZENITH_MAX_PROFILE_ID    32
#define ZENITH_MAX_CORES         8
#define ZENITH_MAX_THERMAL_ZONES 16
#define ZENITH_SOCKET_PATH       "/dev/socket/zenithd"

/* ---- Message types ---- */

typedef enum {
    MSG_HEARTBEAT     = 0x01,
    MSG_GET_STATUS    = 0x10,
    MSG_GET_STATUS_R  = 0x11,
    MSG_SET_PROFILE   = 0x20,
    MSG_SET_PROFILE_R = 0x21,
    MSG_GET_APPS      = 0x30,
    MSG_GET_APPS_R    = 0x31,
    MSG_SET_THERMAL   = 0x40,
    MSG_SET_THERMAL_R = 0x41,
    MSG_CHALLENGE     = 0x50,
    MSG_CHALLENGE_RSP = 0x51,
    MSG_ERROR         = 0xFF,
} msg_type_t;

/* ---- CRC32 ---- */

uint32_t zenith_crc32(const void *data, size_t len);

/* ---- Wire format ---- */

typedef struct __attribute__((packed)) {
    uint8_t  version;       /* ZENITH_MSG_VERSION */
    uint8_t  msg_type;      /* msg_type_t */
    uint16_t payload_len;   /* bytes following this header */
    uint32_t seq;           /* sequence number for request/response matching */
    uint32_t crc;           /* CRC32 over entire packet including this header (crc field = 0) */
} zenith_header_t;

/* ---- Payloads ---- */

typedef struct __attribute__((packed)) {
    uint8_t  foreground_pid;
    uint32_t current_profile_id;
    uint8_t  thermal_zone_count;
    struct {
        uint8_t  zone_id;
        uint32_t temp_milli;  /* millidegrees Celsius */
        uint32_t throttle;    /* 1 = throttled */
    } zones[ZENITH_MAX_THERMAL_ZONES];
    struct {
        uint8_t  online;
        uint32_t capacity_pct_x10; /* percent * 10 */
        int32_t  current_now_ua;   /* microamps, negative = discharge */
        uint32_t drain_pct_per_hr; /* percent per hour * 10 */
    } battery;
} status_payload_t;

typedef struct __attribute__((packed)) {
    char profile_id[ZENITH_MAX_PROFILE_ID]; /* null-terminated */
} set_profile_payload_t;

typedef struct __attribute__((packed)) {
    char packages[ZENITH_MAX_PKG_NAME]; /* null-terminated, comma-separated */
} apps_payload_t;

typedef struct __attribute__((packed)) {
    uint8_t  zone_id;
    uint32_t temp_limit_milli; /* millidegrees, 0 = disable */
} set_thermal_payload_t;

typedef struct __attribute__((packed)) {
    uint8_t  challenge[32];
} challenge_payload_t;

typedef struct __attribute__((packed)) {
    uint8_t  response[32];
} challenge_response_payload_t;

typedef struct __attribute__((packed)) {
    uint8_t  error_code;
    char     message[128]; /* null-terminated */
} error_payload_t;

/* ---- Convenience: total packet size ---- */

static inline size_t zenith_packet_size(uint16_t payload_len) {
    return sizeof(zenith_header_t) + payload_len;
}

#endif /* ZENITH_IPC_PROTOCOL_H */
