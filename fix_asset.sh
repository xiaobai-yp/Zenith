#!/bin/bash
cd /data/data/com.termux/files/home/zenith

# Copy correct binary to assets
cp ~/zenith-daemon-dl/zen-56f83fe/zenithd app/src/main/assets/zenithd
echo "=== asset md5 now ==="
md5sum app/src/main/assets/zenithd
echo "=== systemui in asset? ==="
strings app/src/main/assets/zenithd | grep -c systemui

# Commit
git add app/src/main/assets/zenithd
git commit -m "chore: update daemon binary (systemui/android transient filter)"
git push origin main 2>&1 | tail -2