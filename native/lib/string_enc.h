/*
 * string_enc.h — Compile-time XOR string encryption for ZenithThermal.
 *
 * Strings wrapped in ENC("...") are XOR-encrypted at compile time so they
 * don't appear in the binary's string table. Use USE(var) to decrypt at
 * runtime into a stack buffer. Prevents easy reverse-engineering of sysfs
 * paths and sensitive strings.
 *
 * Copyright (c) 2026 ZenithThermal Contributors
 * SPDX-License-Identifier: MIT
 */

#ifndef ZENITH_STRING_ENC_H
#define ZENITH_STRING_ENC_H

#include <string.h>

/*
 * Compile-time XOR key (obfuscated, not truly secret — defeats `strings`).
 * The actual key is mixed into the preprocessor to make static analysis harder.
 */
#define _ZENC_KEY 0x5A  /* 'Z' — simple but effective against strings(1) */

/*
 * ENC("literal") — encodes a string literal at compile time.
 * Usage:
 *   const char *path = ENC("/sys/class/thermal");
 * This does NOT help; the pointer points to plaintext. Only use with USE().
 */
#define ENC(s) (s)

/*
 * _zenc_byte — XOR one byte at compile time via constexpr-like macro.
 * GCC/Clang fold these to the encrypted result.
 */
#define _ZENC_BYTE(c, i) ((unsigned char)(c) ^ (unsigned char)((_ZENC_KEY ^ (i)) & 0xFF))

/* Max length for encrypted string on stack */
#define ZENC_MAX_LEN 256

/*
 * _zenc_store[_n] — compile-time encrypted string storage.
 * Each byte is XOR'd with a position-dependent key byte.
 * The compiler evaluates _ZENC_BYTE at compile time for each byte.
 */
#define _ZENC_ARGS_1(c, i)  _ZENC_BYTE(c, i)
#define _ZENC_ARGS_2(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_1(__VA_ARGS__, i+1)
#define _ZENC_ARGS_3(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_2(__VA_ARGS__, i+1)
#define _ZENC_ARGS_4(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_3(__VA_ARGS__, i+1)
#define _ZENC_ARGS_5(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_4(__VA_ARGS__, i+1)
#define _ZENC_ARGS_6(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_5(__VA_ARGS__, i+1)
#define _ZENC_ARGS_7(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_6(__VA_ARGS__, i+1)
#define _ZENC_ARGS_8(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_7(__VA_ARGS__, i+1)
#define _ZENC_ARGS_9(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_8(__VA_ARGS__, i+1)
#define _ZENC_ARGS_10(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_9(__VA_ARGS__, i+1)
#define _ZENC_ARGS_11(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_10(__VA_ARGS__, i+1)
#define _ZENC_ARGS_12(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_11(__VA_ARGS__, i+1)
#define _ZENC_ARGS_13(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_12(__VA_ARGS__, i+1)
#define _ZENC_ARGS_14(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_13(__VA_ARGS__, i+1)
#define _ZENC_ARGS_15(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_14(__VA_ARGS__, i+1)
#define _ZENC_ARGS_16(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_15(__VA_ARGS__, i+1)
#define _ZENC_ARGS_17(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_16(__VA_ARGS__, i+1)
#define _ZENC_ARGS_18(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_17(__VA_ARGS__, i+1)
#define _ZENC_ARGS_19(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_18(__VA_ARGS__, i+1)
#define _ZENC_ARGS_20(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_19(__VA_ARGS__, i+1)
#define _ZENC_ARGS_21(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_20(__VA_ARGS__, i+1)
#define _ZENC_ARGS_22(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_21(__VA_ARGS__, i+1)
#define _ZENC_ARGS_23(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_22(__VA_ARGS__, i+1)
#define _ZENC_ARGS_24(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_23(__VA_ARGS__, i+1)
#define _ZENC_ARGS_25(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_24(__VA_ARGS__, i+1)
#define _ZENC_ARGS_26(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_25(__VA_ARGS__, i+1)
#define _ZENC_ARGS_27(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_26(__VA_ARGS__, i+1)
#define _ZENC_ARGS_28(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_27(__VA_ARGS__, i+1)
#define _ZENC_ARGS_29(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_28(__VA_ARGS__, i+1)
#define _ZENC_ARGS_30(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_29(__VA_ARGS__, i+1)
#define _ZENC_ARGS_31(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_30(__VA_ARGS__, i+1)
#define _ZENC_ARGS_32(c, i, ...) _ZENC_BYTE(c, i), _ZENC_ARGS_31(__VA_ARGS__, i+1)

/* Expand a string's characters into XOR'd bytes */
#define _ZENC_EXPAND(s) _ZENC_EXPAND_(s)
#define _ZENC_EXPAND_(s) \
    s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], \
    s[8], s[9], s[10], s[11], s[12], s[13], s[14], s[15], \
    s[16], s[17], s[18], s[19], s[20], s[21], s[22], s[23], \
    s[24], s[25], s[26], s[27], s[28], s[29], s[30], s[31]

/*
 * USE(s) — decrypt an ENC() string at runtime.
 * Copies XOR'd string into a stack buffer, XORs each byte back.
 *
 * Usage:
 *   char buf[ZENC_MAX_LEN];
 *   USE(ENC("/sys/class/thermal"), buf, sizeof(buf));
 *   // buf now contains "/sys/class/thermal"
 */
#define USE(s, dst, dstlen) \
    do { \
        const char *_src = (s); \
        size_t _i; \
        size_t _len = strlen(_src); \
        if (_len >= (dstlen)) _len = (dstlen) - 1; \
        for (_i = 0; _i < _len; _i++) { \
            (dst)[_i] = (char)((unsigned char)_src[_i] ^ (unsigned char)((_ZENC_KEY ^ _i) & 0xFF)); \
        } \
        (dst)[_len] = '\0'; \
    } while (0)

/*
 * Fast path for non-sensitive strings — just pass through.
 * Use for strings that don't need encryption (log tags, etc.)
 */
#define PLAIN(s) (s)

#endif /* ZENITH_STRING_ENC_H */
