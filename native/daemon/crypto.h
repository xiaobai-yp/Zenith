/*
 * crypto.h — Header for ROM signature verification, HMAC auth, anti-debug.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_CRYPTO_H
#define ZENITH_CRYPTO_H

#include <stdint.h>
#include <stddef.h>

/* HMAC key length */
#define ZENITH_HMAC_KEY_LEN  32
#define ZENITH_CHALLENGE_LEN 32

/* ROM signature verification: checks ro.build.fingerprint matches expected */
int crypto_verify_rom(void);

/* HMAC mutual auth: generate challenge for the connecting app */
int crypto_generate_challenge(uint8_t *challenge_out, size_t len);

/* HMAC mutual auth: verify app's response to challenge */
int crypto_verify_response(const uint8_t *challenge, size_t challenge_len,
                           const uint8_t *response, size_t response_len);

/* Anti-debug: returns 1 if debugger attached, 0 otherwise */
int crypto_check_debugger(void);

/* Derive HMAC key from ROM fingerprint (static key for this device) */
int crypto_derive_key(uint8_t *key_out, size_t key_len);

#endif /* ZENITH_CRYPTO_H */
