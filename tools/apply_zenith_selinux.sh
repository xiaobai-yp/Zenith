#!/system/bin/sh
# apply_zenith_selinux.sh — Auto-inject zenithd SELinux policy into a ported ROM.
#
# Usage:
#   Put these 3 files in ONE folder (default: same folder as this script):
#     zenithd.te  file_contexts  property_contexts
#   Then run:  sh apply_zenith_selinux.sh [folder]
#
# What it does:
#   1. Converts zenithd.te  -> CIL, appends to vendor_sepolicy.cil
#   2. Appends file_contexts  -> vendor_file_contexts
#   3. Appends property_contexts -> vendor_property_contexts
#   4. Backs up all 3 originals to /data/local/zenith-selinux-backup/
#   5. restorecon + prompts reboot
#
# Bootloop recovery:
#   sh apply_zenith_selinux.sh --restore
#
# Copyright (c) 2026 ZenithThermal Contributors
# SPDX-License-Identifier: MIT

DIR="${1:-$(dirname "$0")}"
TE="$DIR/zenithd.te"
FC="$DIR/file_contexts"
PC="$DIR/property_contexts"

CIL=/vendor/etc/selinux/vendor_sepolicy.cil
VFC=/vendor/etc/selinux/vendor_file_contexts
VPC=/vendor/etc/selinux/vendor_property_contexts
BACKUP=/data/local/zenith-selinux-backup

fail() { echo "ERROR: $*"; exit 1; }

# ---- restore mode ----
if [ "$1" = "--restore" ]; then
    [ -d "$BACKUP" ] || fail "no backup at $BACKUP"
    mount -o rw,remount /vendor 2>/dev/null || mount -o rw,remount /
    cp "$BACKUP/vendor_sepolicy.cil" "$CIL"
    cp "$BACKUP/vendor_file_contexts" "$VFC"
    cp "$BACKUP/vendor_property_contexts" "$VPC"
    mount -o ro,remount /vendor 2>/dev/null || mount -o ro,remount /
    echo "Restored from backup. Reboot now."
    exit 0
fi

for f in "$TE" "$FC" "$PC"; do
    [ -f "$f" ] || fail "missing: $f (put zenithd.te, file_contexts, property_contexts in $DIR)"
done

# ---- 1. Convert .te -> CIL ----
CIL_RULES="$DIR/_zenithd_converted.cil"
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
}' "$TE" > "$CIL_RULES"
echo "Converted $(grep -c '^(' "$CIL_RULES") CIL rules from zenithd.te"

# ---- 2. Backup ----
mkdir -p "$BACKUP"
mount -o rw,remount /vendor 2>/dev/null || mount -o rw,remount /
cp "$CIL" "$BACKUP/vendor_sepolicy.cil" 2>/dev/null || fail "cannot read $CIL"
cp "$VFC" "$BACKUP/vendor_file_contexts" 2>/dev/null || fail "cannot read $VFC"
cp "$VPC" "$BACKUP/vendor_property_contexts" 2>/dev/null || fail "cannot read $VPC"
echo "Backup -> $BACKUP"

# ---- 3. Append ----
cat "$CIL_RULES" >> "$CIL" || fail "append to $CIL failed"
cat "$FC" >> "$VFC" || fail "append to $VFC failed"
cat "$PC" >> "$VPC" || fail "append to $VPC failed"
echo "Injected: vendor_sepolicy.cil, vendor_file_contexts, vendor_property_contexts"

# ---- 4. Sanity + restorecon ----
if [ ! -s "$CIL" ]; then fail "CIL empty after edit!"; fi
restorecon -R /vendor/etc/selinux/ 2>/dev/null
mount -o ro,remount /vendor 2>/dev/null || mount -o ro,remount /

echo ""
echo "Done. Reboot now:  reboot"
echo "If bootloop:  sh $0 --restore"