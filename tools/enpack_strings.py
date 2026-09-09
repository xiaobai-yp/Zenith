#!/usr/bin/env python3
"""
enpack_strings.py — XOR-encrypt string literals in C source files.

Scans C files for string literals in ENC("...") calls and replaces them
with XOR-encrypted byte arrays. Generates corresponding DEC() calls.

Usage:
    python3 enpack_strings.py [--key 0x5A] [--max-len 256] <file.c> [file2.c ...]

Copyright (c) 2026 ZenithThermal Contributors
SPDX-License-Identifier: MIT
"""

import argparse
import re
import sys

XENC_KEY = 0x5A  # matches string_enc.h _ZENC_KEY


def encrypt_string(s, key):
    """XOR-encrypt a string with position-dependent key."""
    return bytes(((ord(c) ^ ((key ^ i) & 0xFF)) for i, c in enumerate(s)))


def decrypt_string(data, key):
    """Decrypt XOR-encrypted bytes back to string."""
    return ''.join(chr((b ^ ((key ^ i) & 0xFF)) & 0xFF) for i, b in enumerate(data))


def process_file(filepath, key=XENC_KEY, max_len=256):
    """Process a C file, replacing ENC("...") with encrypted variants."""
    with open(filepath, 'r') as f:
        content = f.read()

    # Find all ENC("...") patterns
    pattern = re.compile(r'ENC\("([^"]*)"\)')

    def replace_enc(match):
        s = match.group(1)
        if len(s) > max_len:
            print(f"Warning: string too long ({len(s)} > {max_len}), skipping: {s[:40]}...")
            return match.group(0)

        encrypted = encrypt_string(s, key)
        # Generate hex array
        hex_bytes = ', '.join(f'0x{b:02x}' for b in encrypted)
        hex_bytes += ', 0x00'  # null terminator

        return f'(const char[]){{ {hex_bytes} }}'

    new_content = pattern.sub(replace_enc, content)

    if new_content != content:
        with open(filepath, 'w') as f:
            f.write(new_content)
        count = len(pattern.findall(content))
        print(f"  {filepath}: encrypted {count} strings")
    else:
        print(f"  {filepath}: no ENC() strings found")

    return new_content != content


def main():
    parser = argparse.ArgumentParser(description='Encrypt ENC() string literals in C files')
    parser.add_argument('files', nargs='+', help='C source files to process')
    parser.add_argument('--key', type=lambda x: int(x, 0), default=XENC_KEY,
                        help='XOR key byte (default: 0x5A)')
    parser.add_argument('--max-len', type=int, default=256,
                        help='Max string length (default: 256)')
    parser.add_argument('--dry-run', action='store_true',
                        help='Show what would be changed without writing')
    args = parser.parse_args()

    modified = 0
    for filepath in args.files:
        if args.dry_run:
            print(f"[dry-run] Would process: {filepath}")
        else:
            if process_file(filepath, args.key, args.max_len):
                modified += 1

    print(f"Processed {len(args.files)} files, {modified} modified")
    return 0


if __name__ == '__main__':
    sys.exit(main())
