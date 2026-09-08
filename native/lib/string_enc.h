#ifndef ZENITH_STRING_ENC_H
#define ZENITH_STRING_ENC_H

#include <string.h>

#define _ZENC_KEY 0x5A

#define _ZENC_BYTE(c, i) \
    ((unsigned char)(c) ^ (unsigned char)((_ZENC_KEY ^ (i)) & 0xFF))

/* Max string length (including null) for ENC() macro */
#define ZENC_MAX_LEN 64

/* ENC("literal") — encrypt a string at compile time (function scope only).
 * Expands to a compound literal of XOR-encrypted bytes.
 * DO NOT use at file scope. DO NOT use as standalone expression. */
#define ENC(s)                                                      \
    ((const unsigned char[]){                                        \
        _ZENC_BYTE(((s)[0]), 0), _ZENC_BYTE(((s)[1]), 1),           \
        _ZENC_BYTE(((s)[2]), 2), _ZENC_BYTE(((s)[3]), 3),           \
        _ZENC_BYTE(((s)[4]), 4), _ZENC_BYTE(((s)[5]), 5),           \
        _ZENC_BYTE(((s)[6]), 6), _ZENC_BYTE(((s)[7]), 7),           \
        _ZENC_BYTE(((s)[8]), 8), _ZENC_BYTE(((s)[9]), 9),           \
        _ZENC_BYTE(((s)[10]),10),_ZENC_BYTE(((s)[11]),11),          \
        _ZENC_BYTE(((s)[12]),12),_ZENC_BYTE(((s)[13]),13),          \
        _ZENC_BYTE(((s)[14]),14),_ZENC_BYTE(((s)[15]),15),          \
        _ZENC_BYTE(((s)[16]),16),_ZENC_BYTE(((s)[17]),17),          \
        _ZENC_BYTE(((s)[18]),18),_ZENC_BYTE(((s)[19]),19),          \
        _ZENC_BYTE(((s)[20]),20),_ZENC_BYTE(((s)[21]),21),          \
        _ZENC_BYTE(((s)[22]),22),_ZENC_BYTE(((s)[23]),23),          \
        _ZENC_BYTE(((s)[24]),24),_ZENC_BYTE(((s)[25]),25),          \
        _ZENC_BYTE(((s)[26]),26),_ZENC_BYTE(((s)[27]),27),          \
        _ZENC_BYTE(((s)[28]),28),_ZENC_BYTE(((s)[29]),29),          \
        _ZENC_BYTE(((s)[30]),30),_ZENC_BYTE(((s)[31]),31),          \
        _ZENC_BYTE(((s)[32]),32),_ZENC_BYTE(((s)[33]),33),          \
        _ZENC_BYTE(((s)[34]),34),_ZENC_BYTE(((s)[35]),35),          \
        _ZENC_BYTE(((s)[36]),36),_ZENC_BYTE(((s)[37]),37),          \
        _ZENC_BYTE(((s)[38]),38),_ZENC_BYTE(((s)[39]),39),          \
        _ZENC_BYTE(((s)[40]),40),_ZENC_BYTE(((s)[41]),41),          \
        _ZENC_BYTE(((s)[42]),42),_ZENC_BYTE(((s)[43]),43),          \
        _ZENC_BYTE(((s)[44]),44),_ZENC_BYTE(((s)[45]),45),          \
        _ZENC_BYTE(((s)[46]),46),_ZENC_BYTE(((s)[47]),47),          \
        _ZENC_BYTE(((s)[48]),48),_ZENC_BYTE(((s)[49]),49),          \
        _ZENC_BYTE(((s)[50]),50),_ZENC_BYTE(((s)[51]),51),          \
        _ZENC_BYTE(((s)[52]),52),_ZENC_BYTE(((s)[53]),53),          \
        _ZENC_BYTE(((s)[54]),54),_ZENC_BYTE(((s)[55]),55),          \
        _ZENC_BYTE(((s)[56]),56),_ZENC_BYTE(((s)[57]),57),          \
        _ZENC_BYTE(((s)[58]),58),_ZENC_BYTE(((s)[59]),59),          \
        _ZENC_BYTE(((s)[60]),60),_ZENC_BYTE(((s)[61]),61),          \
        _ZENC_BYTE(((s)[62]),62),_ZENC_BYTE(((s)[63]),63),          \
        0                                                           \
    })

/* USE(ENC("..."), dst, sizeof(dst)) — decrypt ENC string into caller buffer.
 * dst must be char[ZENC_MAX_LEN] or larger. */
#define USE(enc, dst, dstlen) do {                                   \
    const unsigned char *_s = (enc);                                 \
    size_t _i, _len = (dstlen) < ZENC_MAX_LEN ? (dstlen) - 1 : ZENC_MAX_LEN - 1; \
    for (_i = 0; _i < _len; _i++) {                                 \
        if (_s[_i] == 0) { break; }                                 \
        (dst)[_i] = (char)((unsigned char)_s[_i] ^                 \
                           (unsigned char)((_ZENC_KEY ^ _i) & 0xFF)); \
    }                                                                \
    (dst)[_i] = '\0';                                               \
} while (0)

/* PLAIN(s) — passthrough for non-sensitive strings */
#define PLAIN(s) (s)

#endif /* ZENITH_STRING_ENC_H */
