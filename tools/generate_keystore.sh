#!/bin/bash
# generate_keystore.sh — Generate a signing keystore for ZenithThermal APK.
#
# Usage: ./generate_keystore.sh [output_path]
# Default output: keystore/zenith-release.jks
#
# Copyright (c) 2026 ZenithThermal Contributors
# SPDX-License-Identifier: MIT

set -euo pipefail

OUTPUT="${1:-keystore/zenith-release.jks}"
ALIAS="zenith-thermal"
VALIDITY=10000  # ~27 years

echo "Generating signing keystore: $OUTPUT"

mkdir -p "$(dirname "$OUTPUT")"

keytool -genkeypair     -alias "$ALIAS"     -keyalg RSA     -keysize 2048     -validity "$VALIDITY"     -keystore "$OUTPUT"     -storepass zenith123     -keypass zenith123     -dname "CN=ZenithThermal, OU=Development, O=ZenithThermal, L=Unknown, ST=Unknown, C=CN"

echo "Keystore generated: $OUTPUT"
echo "Alias: $ALIAS"
echo "Validity: $VALIDITY days"
echo ""
echo "To use with Gradle, add to gradle.properties:"
echo "  signing.storeFile=$OUTPUT"
echo "  signing.storePass=zenith123"
echo "  signing.keyAlias=$ALIAS"
echo "  signing.keyPass=zenith123"
