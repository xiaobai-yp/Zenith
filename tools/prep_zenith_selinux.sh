#!/system/bin/sh
# prep_zenith_selinux.sh — Convert + merge zenithd SELinux files locally.
# NO auto-inject. Generates modified vendor files + backups in this folder.
# You manually copy to /vendor when ready.
#
# Usage: sh prep_zenith_selinux.sh
#
# This folder must contain:
#   zenithd.te  file_contexts  property_contexts
#
# Output (generated in same folder):
#   vendor_sepolicy.cil.new    (merged CIL)
#   vendor_file_contexts.new   (merged FC)
#   vendor_property_contexts.new (merged PC)
#   zenithd.te.bak, file_contexts.bak, property_contexts.bak (unchanged copies)
#
# Manual apply:
#   adb root
#   adb shell mount -o rw,remount /vendor
#   adb push vendor_sepolicy.cil.new  /vendor/etc/selinux/vendor_sepolicy.cil
#   adb push vendor_file_contexts.new /vendor/etc/selinux/vendor_file_contexts
#   adb push vendor_property_contexts.new /vendor/etc/selinux/vendor_property_contexts
#   adb shell restorecon -R /vendor/etc/selinux/
#   adb shell mount -o ro,remount /vendor
#   adb reboot
#
# Bootloop recovery:
#   adb shell mount -o rw,remount /vendor
#   adb push zenithd.te.bak /dev/null  # files are in this folder
#   adb shell "cp /vendor/etc/selinux/vendor_sepolicy.cil.bak /vendor/etc/selinux/vendor_sepolicy.cil && ..."
#   (or just re-push the originals from your device backup)
#
# Copyright (c) 2026 ZenithThermal Contributors
# SPDX-License-Identifier: MIT

set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
TE="$DIR/zenithd.te"
FC="$DIR/file_contexts"
PC="$DIR/property_contexts"
CIL="$DIR/vendor_sepolicy.cil.new"
VFC="$DIR/vendor_file_contexts.new"
VPC="$DIR/vendor_property_contexts.new"

for f in "$TE" "$FC" "$PC"; do
    [ -f "$f" ] || { echo "ERROR: missing $f"; exit 1; }
done

echo "=== ZenithThermal SELinux Prep ==="
echo "Source folder: $DIR"
echo ""

# ---- 1. Backup originals (just copy .bak in this folder) ----
cp "$TE" "$DIR/zenithd.te.bak"
cp "$FC" "$DIR/file_contexts.bak"
cp "$PC" "$DIR/property_contexts.bak"
echo "Backups: zenithd.te.bak  file_contexts.bak  property_contexts.bak"

# ---- 2. Convert .te -> CIL ----
awk '
function trim(s){ gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
{
    if ($0 ~ /^[ \t]*#/ || $0 ~ /^[ \t]*$/) next
    line=$0
    gsub(/\/\*.*\*\//, "", line)
    if (line ~ /^type /) {
        rest=line; sub(/^type[ \t]+/, "", rest)
        n=split(rest, a, ",")
        t=a[1]; gsub(/[ \t;]/, "", t)
        print "(type " t ")"
        for (i=2; i<=n; i++) {
            attr=a[i]; gsub(/[ \t;]/, "", attr)
            if (attr=="domain" || attr=="exec_type" || attr=="vendor_file_type" ||
                attr=="file_type" || attr=="socket_file" || attr=="data_file_type")
                print "(typeattributeset " attr " (" t "))"
        }
        next
    }
    if (line ~ /^allow /) {
        rest=line; sub(/^allow[ \t]+/, "", rest)
        split(rest, p, /[ \t]+/)
        src=p[1]; tc=p[2]
        n=split(tc, t, ":")
        tgt=t[1]; cls=(n>=2)?t[2]:""
        perms=substr(rest, index(rest, p[2])+length(p[2])+1)
        gsub(/[{};]/, "", perms)
        m=split(perms, pl, /[ \t]+/)
        out="(allow " src " " tgt " (" cls " ("
        for (j=1; j<=m; j++) if (pl[j] != "") out=out pl[j] " "
        print out ")))"
        next
    }
    if (match(line, /^(get|set)_prop\([^,]+,[^)]+\)/)) {
        kind=substr(line, 1, 3)
        x=substr(line, RSTART, RLENGTH)
        sub(/^[^(]*\(/, "", x); sub(/\)$/, "", x)
        split(x, args, ",")
        A=trim(args[1]); PT=trim(args[2])
        if (kind=="get") {
            print "(allow " A " " PT " (file (read open getattr map)))"
        } else {
            print "(allow " A " " PT " (property_service (set)))"
            print "(allow " A " " PT " (file (write append getattr map open)))"
        }
        next
    }
    if (match(line, /^(init|vendor_init)_daemon_domain\([^)]+\)/)) {
        x=substr(line, RSTART, RLENGTH)
        sub(/^[^(]*\(/, "", x); sub(/\)$/, "", x)
        print "(typeattributeset domain (" trim(x) "))"
        next
    }
    print "# skipped: " line
}' "$TE" > "$CIL"
CIL_COUNT=$(grep -c '^(' "$CIL")
echo "Converted: $CIL_COUNT CIL rules"

# ---- 3. Merge file_contexts (append to copy of vendor file) ----
# If vendor_file_contexts exists locally, merge; otherwise just output
if [ -f "$DIR/vendor_file_contexts" ]; then
    cp "$DIR/vendor_file_contexts" "$VFC"
else
    echo "# vendor_file_contexts — copy from device, this script appends entries" > "$VFC"
    echo "# Run on device: adb pull /vendor/etc/selinux/vendor_file_contexts $DIR/" > "$VFC"
fi
cat "$FC" >> "$VFC"
echo "Merged: $VFC"

# ---- 4. Merge property_contexts ----
if [ -f "$DIR/vendor_property_contexts" ]; then
    cp "$DIR/vendor_property_contexts" "$VPC"
else
    echo "# vendor_property_contexts — copy from device, this script appends entries" > "$VPC"
fi
cat "$PC" >> "$VPC"
echo "Merged: $VPC"

# ---- 5. Done ----
echo ""
echo "=== Output files (in $DIR): ==="
echo "  vendor_sepolicy.cil.new        → copy to /vendor/etc/selinux/vendor_sepolicy.cil"
echo "  vendor_file_contexts.new       → copy to /vendor/etc/selinux/vendor_file_contexts"
echo "  vendor_property_contexts.new   → copy to /vendor/etc/selinux/vendor_property_contexts"
echo ""
echo "Manual apply (adb shell):"
echo "  adb push vendor_sepolicy.cil.new /vendor/etc/selinux/vendor_sepolicy.cil"
echo "  adb push vendor_file_contexts.new /vendor/etc/selinux/vendor_file_contexts"
echo "  adb push vendor_property_contexts.new /vendor/etc/selinux/vendor_property_contexts"
echo "  adb shell restorecon -R /vendor/etc/selinux/"
echo "  adb reboot"
