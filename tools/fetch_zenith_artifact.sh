#!/bin/bash
# fetch_zenith_artifact.sh — Pull latest zenithd-release.zip from GitHub Releases
# to /root/zenith-out, verify MD5, extract.
#
# Usage: bash tools/fetch_zenith_artifact.sh
#
# Copyright (c) 2026 ZenithThermal Contributors
# SPDX-License-Identifier: MIT

set -e

REPO="xiaobai-yp/ZenithThermal-"
OUT_DIR="${1:-/root/zenith-out}"
TOKEN=$(cd /root/zenith && git remote get-url origin | grep -oP 'ghp_[^@]+')

mkdir -p "$OUT_DIR"

echo "=== Fetching latest release from $REPO ==="

# Get latest release assets
RELEASE_JSON=$(curl -s -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/$REPO/releases/latest")
TAG=$(echo "$RELEASE_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tag_name',''))")

if [ -z "$TAG" ]; then
    echo "ERROR: no release found. CI may still be building."
    exit 1
fi
echo "Latest release: $TAG"

# Get asset ID for zenithd-release.zip
ASSET_ID=$(echo "$RELEASE_JSON" | python3 -c "
import sys,json
for a in json.load(sys.stdin).get('assets',[]):
    if a['name']=='zenithd-release.zip': print(a['id']); break
")

if [ -z "$ASSET_ID" ]; then
    echo "ERROR: zenithd-release.zip not found in release $TAG"
    exit 1
fi

# Download via API (requires auth for private repos)
echo "Downloading asset $ASSET_ID..."
curl -sL -H "Authorization: token $TOKEN" -H "Accept: application/octet-stream" \
  "https://api.github.com/repos/$REPO/releases/assets/$ASSET_ID" \
  -o "$OUT_DIR/zenithd-release.zip"

# Verify zip integrity
file "$OUT_DIR/zenithd-release.zip" | grep -q "Zip" || {
    echo "ERROR: downloaded file is not a zip"; cat "$OUT_DIR/zenithd-release.zip"; exit 1
}
unzip -t "$OUT_DIR/zenithd-release.zip" > /dev/null 2>&1 || {
    echo "ERROR: corrupted zip"; exit 1
}

# Extract
rm -rf "$OUT_DIR/.staging"
mkdir -p "$OUT_DIR/.staging"
unzip -o "$OUT_DIR/zenithd-release.zip" -d "$OUT_DIR/.staging/" > /dev/null

mv "$OUT_DIR/.staging/app-debug.apk"           "$OUT_DIR/app-debug.apk"
mv "$OUT_DIR/.staging/zenithd"                  "$OUT_DIR/zenithd"
mv "$OUT_DIR/.staging/zenith-rom-module.tar.gz" "$OUT_DIR/zenith-rom-module.tar.gz"
rm -rf "$OUT_DIR/.staging"

# MD5 checksums
echo ""
echo "=== MD5 checksums ==="
cd "$OUT_DIR"
md5sum app-debug.apk zenithd zenith-rom-module.tar.gz > MD5SUMS.txt
cat MD5SUMS.txt

echo ""
echo "=== Done. Output in $OUT_DIR ==="
ls -lh "$OUT_DIR"