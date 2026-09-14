#!/bin/bash
cd /data/data/com.termux/files/home/zenith
echo "=== systemui string in asset binary ==="
strings app/src/main/assets/zenithd | grep -i "systemui"
echo "=== launcher string ==="
strings app/src/main/assets/zenithd | grep -i "launcher"
echo "=== unmap_app ==="
strings app/src/main/assets/zenithd | grep "unmap_app"
echo "=== running daemon md5 ==="
md5sum /data/zenith/zenithd 2>/dev/null || echo "daemon not on disk"