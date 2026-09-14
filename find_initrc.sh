#!/bin/bash
echo "=== cari init.rc zenith ==="
su -c "find /system/etc /vendor/etc /system_ext/etc -name '*.rc' 2>/dev/null | xargs grep -l zenith 2>/dev/null" 
echo "=== isi matching ==="
su -c "grep -rn zenith /system/etc/init* /vendor/etc/init* /system_ext/etc/init* 2>/dev/null | head -20"
echo "=== property ==="
getprop persist.sys.zenith.thermal