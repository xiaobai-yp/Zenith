#!/bin/bash
cd /data/data/com.termux/files/home/zenith

# Verify changes
echo "=== main.rs GLOBAL_PROFILE_ID ==="
grep -n "GLOBAL_PROFILE_ID\|get_global_profile_id\|set_global_profile_id" daemon/rust/src/main.rs | head -15
echo "=== client syncMappings ==="
grep -n "Sync global" app/src/main/kotlin/com/zenith/thermal/ZenithDaemonClient.kt

git add -A
git commit -m "fix: daemon global profile tracker (GLOBAL_PROFILE_ID) + sync global on start

- daemon: separate global profile ID from property (property gets clobbered by per-app setprop)
- unmapped app → fallback ke global tracker, bukan property value terakhir
- app: syncMappings kirim setglobalprofile dulu sebelum map per-app"
git push origin main 2>&1 | tail -2