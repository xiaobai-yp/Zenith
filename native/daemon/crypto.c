#include "crypto.h"
#include "../lib/string_enc.h"

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

/* --- HMAC-SHA256 (minimal self-contained) --- */

typedef struct {
    uint8_t data[64];
    size_t  len;
    uint64_t total;
} sha256_ctx;

static const uint32_t K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

#define ROTR(x,n) (((x)>>(n))|((x)<<(32-(n))))
#define CH(x,y,z) (((x)&(y))^((~(x))&(z)))
#define MAJ(x,y,z) (((x)&(y))^((x)&(z))^((y)&(z)))
#define EP0(x) (ROTR(x,2)^ROTR(x,13)^ROTR(x,22))
#define EP1(x) (ROTR(x,6)^ROTR(x,11)^ROTR(x,25))
#define SIG0(x) (ROTR(x,7)^ROTR(x,18)^((x)>>3))
#define SIG1(x) (ROTR(x,17)^ROTR(x,19)^((x)>>10))

static void sha256_compress(sha256_ctx *ctx, const uint8_t block[64]) {
    uint32_t W[64], a, b, c, d, e, f, g, h, t1, t2;
    for (int i = 0; i < 16; i++)
        W[i] = ((uint32_t)block[i*4]<<24)|((uint32_t)block[i*4+1]<<16)|
                ((uint32_t)block[i*4+2]<<8)|(uint32_t)block[i*4+3];
    for (int i = 16; i < 64; i++)
        W[i] = SIG1(W[i-2])+W[i-7]+SIG0(W[i-15])+W[i-16];
    a=0x6a09e667; b=0xbb67ae85; c=0x3c6ef372; d=0xa54ff53a;
    e=0x510e527f; f=0x9b05688c; g=0x1f83d9ab; h=0x5be0cd19;
    for (int i = 0; i < 64; i++) {
        t1=h+EP1(e)+CH(e,f,g)+K[i]+W[i];
        t2=EP0(a)+MAJ(a,b,c);
        h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    uint32_t v[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                   0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    v[0]+=a; v[1]+=b; v[2]+=c; v[3]+=d; v[4]+=e; v[5]+=f; v[6]+=g; v[7]+=h;
    for (int i = 0; i < 8; i++) {
        ctx->data[i*4]=(v[i]>>24)&0xff; ctx->data[i*4+1]=(v[i]>>16)&0xff;
        ctx->data[i*4+2]=(v[i]>>8)&0xff; ctx->data[i*4+3]=v[i]&0xff;
    }
}

static void sha256_init(sha256_ctx *ctx) {
    static const uint8_t iv[32]={
        0x6a,0x09,0xe6,0x67,0xbb,0x67,0xae,0x85,
        0x3c,0x6e,0xf3,0x72,0xa5,0x4f,0x53,0xa5,
        0x51,0x0e,0x52,0x7f,0x9b,0x05,0x68,0x8c,
        0x1f,0x83,0xd9,0xab,0x5b,0xe0,0xcd,0x19};
    memcpy(ctx->data, iv, 32);
    ctx->len = 0;
    ctx->total = 0;
}

static void sha256_update(sha256_ctx *ctx, const uint8_t *data, size_t len) {
    size_t fill = 64 - (ctx->total % 64);
    ctx->total += len;
    if (ctx->len && len >= fill) {
        memcpy(ctx->data + 32 + ctx->len, data, fill);
        sha256_compress(ctx, ctx->data + 32);
        data += fill; len -= fill; ctx->len = 0;
    }
    while (len >= 64) {
        sha256_compress(ctx, data);
        data += 64; len -= 64;
    }
    if (len > 0) {
        memcpy(ctx->data + 32 + ctx->len, data, len);
        ctx->len += len;
    }
}

static void sha256_final(sha256_ctx *ctx, uint8_t out[32]) {
    uint64_t bits = ctx->total * 8;
    uint8_t pad = (ctx->len < 56) ? (56 - ctx->len) : (120 - ctx->len);
    uint8_t padbuf[128] = {0};
    padbuf[0] = 0x80;
    sha256_update(ctx, padbuf, pad);
    uint8_t lenpad[8];
    for (int i = 7; i >= 0; i--) { lenpad[i] = bits & 0xff; bits >>= 8; }
    sha256_update(ctx, lenpad, 8);
    memcpy(out, ctx->data, 32);
}

/* HMAC-SHA256 */
static void hmac_sha256(const uint8_t *key, size_t keylen,
                        const uint8_t *data, size_t datalen,
                        uint8_t out[32]) {
    uint8_t k_pad[64];
    sha256_ctx inner, outer;

    /* If key > block size, hash it first */
    uint8_t k_hash[32];
    if (keylen > 64) {
        sha256_init(&inner);
        sha256_update(&inner, key, keylen);
        sha256_final(&inner, k_hash);
        key = k_hash; keylen = 32;
    }

    /* Inner: SHA256((key ^ ipad) || data) */
    memset(k_pad, 0x36, 64);
    for (size_t i = 0; i < keylen; i++) k_pad[i] ^= key[i];
    sha256_init(&inner);
    sha256_update(&inner, k_pad, 64);
    sha256_update(&inner, data, datalen);
    uint8_t inner_hash[32];
    sha256_final(&inner, inner_hash);

    /* Outer: SHA256((key ^ opad) || inner_hash) */
    memset(k_pad, 0x5c, 64);
    for (size_t i = 0; i < keylen; i++) k_pad[i] ^= key[i];
    sha256_init(&outer);
    sha256_update(&outer, k_pad, 64);
    sha256_update(&outer, inner_hash, 32);
    sha256_final(&outer, out);
}

/* --- Crypto key (compile-time encrypted) --- */

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

/* --- Client APK signature verification (package name check) --- */

int crypto_verify_apk_signature(int fd) {
    if (fd < 0) return -1;

    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) < 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "SO_PEERCRED failed: %s", strerror(errno));
        return -1;
    }

    char cmdline[256];
    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/%d/cmdline", (int)cred.pid);
    int proc_fd = open(proc_path, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(proc_fd, cmdline, sizeof(cmdline) - 1);
    close(proc_fd);
    if (n <= 0) return -1;
    cmdline[n] = '\0';

    const char *expected[2] = {"com.zenith.thermal", "com.zenith.thermal.debug"};
    for (int i = 0; i < 2; i++) {
        if (strncmp(cmdline, expected[i], strlen(expected[i])) == 0) return 0;
    }
    __android_log_print(ANDROID_LOG_WARN, TAG, "Rejected: %s", cmdline);
    return -1;
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
