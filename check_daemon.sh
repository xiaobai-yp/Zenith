#!/bin/bash
cd /data/data/com.termux/files/home/zenith
echo "=== main.rs filter ==="
grep -n "systemui\|starts_with(\"android" daemon/rust/src/main.rs
echo "=== asset md5 ==="
md5sum app/src/main/assets/zenithd
echo "=== daemon git log ==="
git log --oneline -3 -- daemon/rust/src/main.rs
