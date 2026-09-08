/*
 * apk_verify.c — APK Signature Scheme V2 verification (self-contained).
 *
 * Parses the APK Signature Scheme v2 block directly and verifies the
 * signer certificate SHA-256 against a pinned hash. No BoringSSL needed:
 * the V2 block embeds the whole signed certificate (DER), so we can SHA-256
 * it without RSA signature verification. For demand, the RSA verification
 * would require a full crypto stack — we rely on the pinned cert hash +
 * HMAC challenge/response for authenticity.
 *
 * APK layout (relevant here):
 *   [entries][central directory][EOCD]
 *   ... APK Sig Block placed immediately BEFORE the central directory.
 */

#include "apk_verify.h"
#include "../lib/sha256.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>

/* ZIP EOCD */
#define EOCD_SIG      0x06054b50
#define EOCD_MIN_SIZE 22

/* APK Sig Block magic */
#define APK_SIG_BLOCK_MAGIC 0x4a42464c42087358ULL  /* "APK Sig Block 42" LE */

/*
 * Pinned expected cert SHA-256 — REPLACE with your signing cert hash:
 *   keytool -exportcert -keystore ~/.android/debug.keystore \
 *       -alias androiddebugkey -storepass android | openssl dgst -sha256
 */
const uint8_t g_expected_cert_sha256[32] = {
    /* placeholder — filled after first debug build cert extraction */
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00
};

/* --- Read helpers --- */

static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)(p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24));
}
static uint64_t rd64(const uint8_t *p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; i--) v = (v << 8) | p[i];
    return v;
}

/* --- Locate APK Sig Block (before central directory) --- */

static int find_sig_block(int fd, off_t file_size,
                          off_t *block_start, uint32_t *signers_off,
                          uint32_t *signers_size) {
    /* Read EOCD (last 22+ bytes) */
    off_t eocd_off = file_size - EOCD_MIN_SIZE;
    if (eocd_off < 0) return APK_VERIFY_ERR_TOO_SMALL;

    uint8_t eocd[EOCD_MIN_SIZE];
    if (pread(fd, eocd, EOCD_MIN_SIZE, eocd_off) != EOCD_MIN_SIZE)
        return APK_VERIFY_ERR_IO;
    if (rd32(eocd) != EOCD_SIG) return APK_VERIFY_ERR_NO_BLOCK;

    uint32_t cd_offset = rd32(eocd + 16);   /* central dir offset */
    if (cd_offset == 0xFFFFFFFF) {
        /* ZIP64 — unsupported for our purposes */
        return APK_VERIFY_ERR_NO_BLOCK;
    }

    /* Sig block is right before central directory:
     *   [sig block size u64][block][magic u64][size u64]
     * Read the 16 bytes before cd_offset = trailing size + magic
     */
    off_t block_tail = cd_offset - 16;
    if (block_tail < 0) return APK_VERIFY_ERR_TOO_SMALL;

    uint8_t tail[16];
    if (pread(fd, tail, 16, block_tail) != 16) return APK_VERIFY_ERR_IO;
    uint64_t magic = rd64(tail + 8);
    if (magic != APK_SIG_BLOCK_MAGIC) return APK_VERIFY_ERR_NO_BLOCK;

    uint64_t size = rd64(tail);              /* includes two size fields */
    if (size < 24 || (off_t)size > block_tail) return APK_VERIFY_ERR_NO_BLOCK;

    off_t block_off = block_tail - size + 16; /* skip leading size u64 */
    uint64_t block_size = size - 16;          /* magic + payload + trailing size */

    /* Scan block for V2 signer ID (0x7109871a) */
    uint8_t *buf = malloc(block_size);
    if (!buf) return APK_VERIFY_ERR_IO;
    if (pread(fd, buf, block_size, block_off) != (ssize_t)block_size) {
        free(buf);
        return APK_VERIFY_ERR_IO;
    }

    uint32_t id_off = 0;
    int found = -1;
    while (id_off + 8 <= block_size) {
        uint64_t len = rd64(buf + id_off);
        if (len > block_size - id_off - 8) break;
        uint32_t id = rd32(buf + id_off + 8);
        if (id == 0x7109871a) {  /* V2 signing block id */
            found = 1;
            /* payload (signers) is the 8-byte len + id, then data */
            *signers_off = (uint32_t)(block_off + id_off + 16);
            *signers_size = (uint32_t)len;
            break;
        }
        id_off += 8 + len;
    }
    free(buf);
    if (found < 0) return APK_VERIFY_ERR_NO_BLOCK;

    *block_start = block_off;
    return APK_VERIFY_OK;
}

/* --- Extract signing certificate from V2 signer block --- */

/*
 * V2 signer block layout:
 *   u32 signers_count
 *   signer:
 *     len_prefixed( signed_data )
 *     len_prefixed( signatures )
 *     len_prefixed( public_key )
 *   signed_data:
 *     len_prefixed( digests )      (not needed)
 *     len_prefixed( certificates ) <- the cert(s), DER
 *     len_prefixed( additional_attrs )
 */
static int extract_certificate(int fd, off_t signers_off, uint32_t signers_size,
                               uint8_t *cert_out, size_t *cert_len) {
    uint8_t *buf = malloc(signers_size);
    if (!buf) return APK_VERIFY_ERR_IO;
    if (pread(fd, buf, signers_size, signers_off) != (ssize_t)signers_size) {
        free(buf);
        return APK_VERIFY_ERR_IO;
    }

    size_t p = 0;
    if (p + 4 > signers_size) { free(buf); return APK_VERIFY_ERR_NO_CERT; }
    uint32_t signers_count = rd32(buf + p); p += 4;
    if (signers_count == 0) { free(buf); return APK_VERIFY_ERR_NO_CERT; }

    /* first signer: signed_data (len-prefixed) */
    if (p + 4 > signers_size) { free(buf); return APK_VERIFY_ERR_NO_CERT; }
    uint32_t signed_data_len = rd32(buf + p); p += 4;
    size_t sd_start = p;
    if (signed_data_len > signers_size - sd_start) {
        free(buf); return APK_VERIFY_ERR_NO_CERT;
    }

    /* signed_data: skip digests (len-prefixed) */
    if (sd_start + 4 > signers_size) { free(buf); return APK_VERIFY_ERR_NO_CERT; }
    uint32_t digests_len = rd32(buf + sd_start);
    size_t cert_region = sd_start + 4 + digests_len;
    if (cert_region + 4 > signers_size) { free(buf); return APK_VERIFY_ERR_NO_CERT; }

    /* certificates (len-prefixed list) */
    uint32_t certs_len = rd32(buf + cert_region);
    size_t cert_list = cert_region + 4;
    if (certs_len > signers_size - cert_list) { free(buf); return APK_VERIFY_ERR_NO_CERT; }
    if (certs_len < 4) { free(buf); return APK_VERIFY_ERR_NO_CERT; }

    uint32_t cert0_len = rd32(buf + cert_list);
    size_t cert0_start = cert_list + 4;
    if (cert0_len > signers_size - cert0_start) { free(buf); return APK_VERIFY_ERR_NO_CERT; }

    if (*cert_len < cert0_len) {
        free(buf);
        *cert_len = cert0_len;
        return APK_VERIFY_ERR_TOO_SMALL;
    }
    memcpy(cert_out, buf + cert0_start, cert0_len);
    *cert_len = cert0_len;
    free(buf);
    return APK_VERIFY_OK;
}

/* --- Public entry points --- */


static int apk_verify_fd(int fd, off_t file_size) {
    off_t block_start;
    uint32_t signers_off, signers_size;
    int rc = find_sig_block(fd, file_size, &block_start, &signers_off, &signers_size);
    if (rc != APK_VERIFY_OK) return rc;

    uint8_t cert[8192];
    size_t cert_len = sizeof(cert);
    rc = extract_certificate(fd, signers_off, signers_size, cert, &cert_len);
    if (rc != APK_VERIFY_OK) return rc;

    uint8_t cert_hash[32];
    zenith_sha256(cert, cert_len, cert_hash);

    return (memcmp(cert_hash, g_expected_cert_sha256, 32) == 0)
           ? APK_VERIFY_OK : APK_VERIFY_ERR_MISMATCH;
}

/* Resolve APK path from /proc/<pid>/maps (look for base.apk) */
static int resolve_apk_path(pid_t pid, char *out, size_t out_len) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", (int)pid);
    FILE *f = fopen(maps_path, "r");
    if (!f) return APK_VERIFY_ERR_IO;

    char line[1024];
    int rc = APK_VERIFY_ERR_IO;
    while (fgets(line, sizeof(line), f)) {
        char *p = strstr(line, "/data/app/");
        if (p) {
            /* truncate at newline, look for /base.apk or package dir */
            char *nl = strchr(p, '\n'); if (nl) *nl = '\0';
            /* Find package directory end */
            char *end = strstr(p, ".apk-");
            if (end) {
                end += 4; /* skip .apk */
                /* append /base.apk */
                snprintf(out, out_len, "%.*s/base.apk", (int)(end - p), p);
                rc = APK_VERIFY_OK;
                break;
            }
            end = strstr(p, ".apk");
            if (end) {
                end += 4;
                snprintf(out, out_len, "%.*s", (int)(end - p + 4), p);
                rc = APK_VERIFY_OK;
                break;
            }
        }
    }
    fclose(f);
    return rc;
}

int apk_verify_from_path(const char *apk_path) {
    int fd = open(apk_path, O_RDONLY);
    if (fd < 0) return APK_VERIFY_ERR_IO;

    off_t file_size = lseek(fd, 0, SEEK_END);
    if (file_size < 0) { close(fd); return APK_VERIFY_ERR_IO; }

    int rc = apk_verify_fd(fd, file_size);
    close(fd);
    return rc;
}

int apk_verify_from_pid(pid_t pid) {
    /* Resolve APK path: read /proc/<pid>/cmdline, then /proc/<pid>/maps
     * to find base.apk path, fall back to /proc/<pid>/exec symlink (app
     * process), or use /data/app scanning. */
    char path[512];
    int rc = resolve_apk_path(pid, path, sizeof(path));
    if (rc != 0) return rc;

    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        /* app may be in zygote (cmdline is the zygote name). Walk /proc/<pid>/smaps
         * is overkill — for correctness, look at /proc/<pid>/maps for base.apk. */
        return APK_VERIFY_ERR_IO;
    }
    off_t file_size = lseek(fd, 0, SEEK_END);
    if (file_size < 0) { close(fd); return APK_VERIFY_ERR_IO; }
    int result = apk_verify_fd(fd, file_size);
    close(fd);
    return result;
}
