#!/bin/bash
echo "=== init.rc zenith properties ==="
su -c "cat /system/etc/init/hw/init.rc" 2>/dev/null | grep -i -A 5 "zenith" || echo "no zenith in init.rc"
echo "=== persist.sys.zenith.thermal ==="
getprop persist.sys.zenith.thermal