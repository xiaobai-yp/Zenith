/*
 * crypto.c — ROM fingerprint check, HMAC challenge, anti-debug via TracerPid.
 *
 * Implements the crypto.h interface:
 *   - crypto_verify_rom(): checks ro.build.fingerprint property
 *   - crypto_generate_challenge() / crypto_verify_response(): HMAC mutual auth
 *   - crypto_check_debugger(): reads /proc/self/status TracerPid
 *   - crypto_derive_key(): XOR-based key derivation from ROM fingerprint
 *
 * NOTE: This is a skeleton — real deployment should use libsodium or BoringSSL
 * for actual HMAC-SHA256. The current implementation uses a simple XOR-MAC
 * that is sufficient for the wire protocol CRC validation but should be
 * upgraded for production.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#include "crypto.h"
#include "sysfs_monitor.h"
#include "../lib/string_enc.h"

#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <time.h>

#define TAG "ZCrypto"

/* Built-in HMAC-like key (obfuscated). In production, derive from ROM. */
static const uint8_t g_base_key[ZENITH_HMAC_KEY_LEN] = {
    0x5A, 0x65, 0x6E, 0x69, 0x74, 0x68, 0x54, 0x68,
    0x65, 0x72, 0x6D, 0x61, 0x6C, 0x44, 0x65, 0x6D,
    0x6F, 0x4B, 0x65, 0x79, 0x30, 0x31, 0x32, 0x33,
    0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x41, 0x42
};

/* ---- ROM verification ---- */

int crypto_verify_rom(void)
{
    char fingerprint[512] = {0};
    char cmd_buf[256] = {0};

    char prop_path[MAX_PATH_LEN];
    USE(ENC("/system/build.prop"), prop_path, sizeof(prop_path));

    FILE *f = fopen(prop_path, "r");
    if (!f) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "Cannot open %s", prop_path);
        return -1;
    }

    while (fgets(cmd_buf, sizeof(cmd_buf), f) != NULL) {
        if (strncmp(cmd_buf, "ro.build.fingerprint=", 21) == 0) {
            char *val = cmd_buf + 21;
            /* Trim newline */
            size_t len = strlen(val);
            while (len > 0 && (val[len-1] == '\n' || val[len-1] == '\r'))
                val[--len] = '\0';
            strncpy(fingerprint, val, sizeof(fingerprint) - 1);
            break;
        }
    }
    fclose(f);

    if (fingerprint[0] == '\0') {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "ro.build.fingerprint not found");
        return -1;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "ROM fingerprint: %s", fingerprint);
    /* Any valid fingerprint is accepted — device-specific locking
     * can be added by comparing against a whitelist. */
    return 0;
}

/* ---- HMAC-like key derivation ---- */

int crypto_derive_key(uint8_t *key_out, size_t key_len)
{
    if (!key_out || key_len == 0) return -1;

    size_t copy_len = key_len < ZENITH_HMAC_KEY_LEN ? key_len : ZENITH_HMAC_KEY_LEN;
    memcpy(key_out, g_base_key, copy_len);

    /* XOR with ROM fingerprint for device-specific key */
    char fingerprint[256] = {0};
    char cmd_buf[256] = {0};
    char prop_path[MAX_PATH_LEN];
    USE(ENC("/system/build.prop"), prop_path, sizeof(prop_path));

    FILE *f = fopen(prop_path, "r");
    if (f) {
        while (fgets(cmd_buf, sizeof(cmd_buf), f) != NULL) {
            if (strncmp(cmd_buf, "ro.build.fingerprint=", 21) == 0) {
                char *val = cmd_buf + 21;
                size_t len = strlen(val);
                while (len > 0 && (val[len-1] == '\n' || val[len-1] == '\r'))
                    val[--len] = '\0';
                strncpy(fingerprint, val, sizeof(fingerprint) - 1);
                break;
            }
        }
        fclose(f);
    }

    size_t fp_len = strlen(fingerprint);
    for (size_t i = 0; i < copy_len; i++) {
        key_out[i] ^= (uint8_t)fingerprint[i % (fp_len > 0 ? fp_len : 1)];
    }

    return (int)copy_len;
}

/* ---- HMAC challenge/response ---- */

int crypto_generate_challenge(uint8_t *challenge_out, size_t len)
{
    if (!challenge_out || len == 0) return -1;

    /* Generate pseudo-random challenge using monotonic time + PID.
     * In production, use getrandom() or /dev/urandom. */
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);

    uint32_t seed = (uint32_t)(ts.tv_sec ^ ts.tv_nsec ^ (uint32_t)getpid());

    for (size_t i = 0; i < len && i < ZENITH_CHALLENGE_LEN; i++) {
        seed = seed * 1103515245 + 12345; /* LCG */
        challenge_out[i] = (uint8_t)((seed >> 16) & 0xFF);
    }
    return 0;
}

/* Simple XOR-MAC: response = HMAC(key, challenge) using base_key ⊕ challenge */
int crypto_verify_response(const uint8_t *challenge, size_t challenge_len,
                           const uint8_t *response, size_t response_len)
{
    if (!challenge || !response) return -1;
    if (challenge_len == 0 || response_len == 0) return -1;

    uint8_t expected[ZENITH_CHALLENGE_LEN] = {0};
    size_t mac_len = challenge_len < ZENITH_CHALLENGE_LEN ? challenge_len : ZENITH_CHALLENGE_LEN;

    for (size_t i = 0; i < mac_len; i++) {
        expected[i] = g_base_key[i % ZENITH_HMAC_KEY_LEN] ^ challenge[i];
    }

    if (response_len < mac_len) return -1;
    return (memcmp(expected, response, mac_len) == 0) ? 0 : -1;
}

/* ---- Anti-debug ---- */

int crypto_check_debugger(void)
{
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0; /* Can't check — assume not debugged */

    char line[256];
    while (fgets(line, sizeof(line), f) != NULL) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = atoi(line + 10);
            fclose(f);
            if (pid != 0) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                                    "Debugger detected (TracerPid=%d)", pid);
                return 1;
            }
            return 0;
        }
    }
    fclose(f);
    return 0;
}
