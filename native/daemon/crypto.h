#ifndef ZENITH_CRYPTO_H
#define ZENITH_CRYPTO_H

#include <stdint.h>
#include <stddef.h>

#define ZENITH_HMAC_KEY_LEN  32
#define ZENITH_CHALLENGE_LEN 32

// socket_server tinggal panggil dua baris:
//   crypto_set_client_fd(fd);
//   int ok = crypto_verify_apk_signature();
void crypto_set_client_fd(int fd);
int  crypto_verify_apk_signature(void);

int crypto_generate_challenge(uint8_t *challenge_out, size_t len);
int crypto_verify_response(const uint8_t *challenge, size_t challenge_len,
                           const uint8_t *response, size_t response_len);
int crypto_check_debugger(void);

#endif
