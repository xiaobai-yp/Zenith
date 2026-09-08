#include "crypto.h"
#include "apk_verify.h"
#include "../lib/string_enc.h"
#include "../lib/sha256.h"

#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>

#define TAG "ZCrypto"

/* --- HMAC-SHA256 using shared sha256.h --- */

static void hmac_sha256(const uint8_t *key, size_t keylen,
                        const uint8_t *data, size_t datalen,
                        uint8_t out[32]) {
    uint8_t k_pad[64];
    zenith_sha256_ctx inner, outer;

    uint8_t k_hash[32];
    if (keylen > 64) {
        zenith_sha256_init(&inner);
        zenith_sha256_update(&inner, key, keylen);
        zenith_sha256_final(&inner, k_hash);
        key = k_hash; keylen = 32;
    }

    memset(k_pad, 0x36, 64);
    for (size_t i = 0; i < keylen; i++) k_pad[i] ^= key[i];
    zenith_sha256_init(&inner);
    zenith_sha256_update(&inner, k_pad, 64);
    zenith_sha256_update(&inner, data, datalen);
    uint8_t inner_hash[32];
    zenith_sha256_final(&inner, inner_hash);

    memset(k_pad, 0x5c, 64);
    for (size_t i = 0; i < keylen; i++) k_pad[i] ^= key[i];
    zenith_sha256_init(&outer);
    zenith_sha256_update(&outer, k_pad, 64);
    zenith_sha256_update(&outer, inner_hash, 32);
    zenith_sha256_final(&outer, out);
}

/* --- HMAC key (compile-time XOR obfuscated) --- */

static uint8_t g_hmac_key[ZENITH_HMAC_KEY_LEN];

static const uint8_t g_hmac_key_enc[ZENITH_HMAC_KEY_LEN] = {
    0x00, 0x3f, 0x3c, 0x3b, 0x2d, 0x08, 0x29, 0x2e,
    0x3f, 0x1c, 0x3e, 0x3e, 0x33, 0x13, 0x3c, 0x31,
    0x38, 0x1a, 0x31, 0x37, 0x6a, 0x6d, 0x68, 0x63,
    0x61, 0x6c, 0x69, 0x6e, 0x3c, 0x33, 0x68, 0x38
};

static void init_key(void) {
    static int done = 0;
    if (done) return;
    done = 1;
    for (size_t i = 0; i < ZENITH_HMAC_KEY_LEN; i++)
        g_hmac_key[i] = g_hmac_key_enc[i] ^ (uint8_t)((0x5A ^ i) & 0xFF);
}

/* --- Secure challenge generation --- */

int crypto_generate_challenge(uint8_t *challenge_out, size_t len) {
    if (!challenge_out || len == 0) return -1;
    size_t readlen = len < ZENITH_CHALLENGE_LEN ? len : ZENITH_CHALLENGE_LEN;
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, challenge_out, readlen);
    close(fd);
    return (n == (ssize_t)readlen) ? 0 : -1;
}

/* --- HMAC challenge/response verification --- */

int crypto_verify_response(const uint8_t *challenge, size_t challenge_len,
                           const uint8_t *response, size_t response_len) {
    if (!challenge || !response) return -1;
    if (challenge_len == 0 || response_len < 32) return -1;

    init_key();
    uint8_t expected[32];
    hmac_sha256(g_hmac_key, ZENITH_HMAC_KEY_LEN,
                challenge, challenge_len < ZENITH_CHALLENGE_LEN ? challenge_len : ZENITH_CHALLENGE_LEN,
                expected);
    return (memcmp(expected, response, 32) == 0) ? 0 : -1;
}

/* --- Client APK signature verification --- */

int crypto_verify_apk_signature(int fd) {
    if (fd < 0) return -1;

    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) < 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "SO_PEERCRED failed: %s", strerror(errno));
        return -1;
    }

    /* Package name check (first layer) */
    char cmdline[256];
    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/%d/cmdline", (int)cred.pid);
    int proc_fd = open(proc_path, O_RDONLY);
    if (proc_fd < 0) return -1;
    ssize_t n = read(proc_fd, cmdline, sizeof(cmdline) - 1);
    close(proc_fd);
    if (n <= 0) return -1;
    cmdline[n] = '\0';

    const char *expected[2] = {"com.zenith.thermal", "com.zenith.thermal.debug"};
    int pkg_match = 0;
    for (int i = 0; i < 2; i++) {
        if (strncmp(cmdline, expected[i], strlen(expected[i])) == 0) {
            pkg_match = 1;
            break;
        }
    }
    if (!pkg_match) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Rejected: %s", cmdline);
        return -1;
    }

    /* APK V2 signing cert verification (second layer) */
    int v2_result = apk_verify_from_pid(cred.pid);
    if (v2_result != APK_VERIFY_OK) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "APK cert verify failed: %d", v2_result);
        return -1;
    }

    return 0;
}

/* --- Anti-debug --- */

int crypto_check_debugger(void) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = atoi(line + 10);
            fclose(f);
            return (pid != 0) ? 1 : 0;
        }
    }
    fclose(f);
    return 0;
}
