#ifndef ZENITH_SHA256_H
#define ZENITH_SHA256_H

#include <stdint.h>
#include <stddef.h>

typedef struct {
    uint8_t data[64];
    size_t  len;
    uint64_t total;
} zenith_sha256_ctx;

void zenith_sha256_init(zenith_sha256_ctx *ctx);
void zenith_sha256_update(zenith_sha256_ctx *ctx, const uint8_t *data, size_t len);
void zenith_sha256_final(zenith_sha256_ctx *ctx, uint8_t out[32]);
void zenith_sha256(const uint8_t *data, size_t len, uint8_t out[32]);

#endif
