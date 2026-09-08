#!/bin/bash
# fetch_zenith_artifact.sh — Pull latest zenithd-release.zip from GitHub Actions
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

# Find latest run with zenithd-release artifact
RUN_ID=$(curl -s -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/$REPO/actions/runs?per_page=1" | \
  python3 -c "import sys,json; print(json.load(sys.stdin)['workflow_runs'][0]['id'])")
echo "Latest run: $RUN_ID"

ART_ID=$(curl -s -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts" | \
  python3 -c "
import sys,json
for a in json.load(sys.stdin).get('artifacts',[]):
    if a['name']=='zenithd-release':
        print(a['id']); break
")
if [ -z "$ART_ID" ]; then
    echo "ERROR: zenithd-release artifact not found (run may still be building)"
    exit 1
fi
echo "Artifact: $ART_ID"

# Download
curl -sL -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/actions/artifacts/$ART_ID/zip" \
  -o "$OUT_DIR/zenithd-release.zip"

# Verify zip integrity
unzip -t "$OUT_DIR/zenithd-release.zip" > /dev/null 2>&1 || {
    echo "ERROR: corrupted zip"; exit 1
}

# Extract to staging
rm -rf "$OUT_DIR/.staging"
mkdir -p "$OUT_DIR/.staging"
unzip -o "$OUT_DIR/zenithd-release.zip" -d "$OUT_DIR/.staging/" > /dev/null

# Move files out
mv "$OUT_DIR/.staging/app-debug.apk"      "$OUT_DIR/app-debug.apk"
mv "$OUT_DIR/.staging/zenithd"            "$OUT_DIR/zenithd"
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