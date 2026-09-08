#ifndef ZENITH_APK_VERIFY_H
#define ZENITH_APK_VERIFY_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

/*
 * APK Signature Scheme V2 verification (self-contained, no BoringSSL).
 *
 * Flow:
 *   1. From /proc/<pid>/cmdline → resolve APK path
 *   2. Parse ZIP EOCD → find APK Sig Block (magic 0xa434262a)
 *   3. Parse V2 signing block → extract signing certificate
 *   4. SHA256 certificate → compare with pinned hash
 *
 * Pin the correct cert hash after first successful build:
 *   keytool -exportcert -keystore debug.keystore -alias androiddebugkey \
 *     | openssl dgst -sha256
 * Then paste into g_expected_cert_sha256 below.
 */

#define APK_VERIFY_OK              0
#define APK_VERIFY_ERR_IO         -1
#define APK_VERIFY_ERR_NO_CERT    -2
#define APK_VERIFY_ERR_NO_BLOCK   -3
#define APK_VERIFY_ERR_MISMATCH   -4
#define APK_VERIFY_ERR_TOO_SMALL  -5

/* Expected signing certificate SHA-256 hash (debug keystore).
 * Update after first build: extract cert hash from debug.keystore. */
extern const uint8_t g_expected_cert_sha256[32];

/* Resolve APK path from PID via /proc/<pid>/cmdline, then verify.
 * Returns APK_VERIFY_OK if signature matches pinned cert. */
int apk_verify_from_pid(pid_t pid);

/* Verify APK at the given path directly.
 * Returns APK_VERIFY_OK if signature matches pinned cert. */
int apk_verify_from_path(const char *apk_path);

#endif
