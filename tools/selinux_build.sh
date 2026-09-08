#!/system/bin/sh
set -e

CIL="vendor_sepolicy.cil"
FC="vendor_file_contexts"
PC="vendor_property_contexts"
TE="zenithd.te"
FC_IN="file_contexts"
PC_IN="property_contexts"
TMP=".zenith_cil.tmp"
FC_TMP=".zenith_fc.tmp"
PC_TMP=".zenith_pc.tmp"

[ -f "$TE" ]    || { echo "ERROR: $TE not found"; exit 1; }
[ -f "$FC_IN" ] || { echo "ERROR: $FC_IN not found"; exit 1; }
[ -f "$PC_IN" ] || { echo "ERROR: $PC_IN not found"; exit 1; }
[ -f "$CIL" ]   || { echo "ERROR: $CIL not found"; exit 1; }
[ -f "$FC" ]    || { echo "ERROR: $FC not found"; exit 1; }
[ -f "$PC" ]    || { echo "ERROR: $PC not found"; exit 1; }

for f in "$CIL" "$FC" "$PC"; do
    [ -f "$f.orig" ] || { cp "$f" "$f.orig"; echo "Backup: $f.orig"; }
done

# --- .te -> CIL rules (pure sed/awk) ---
awk '
    /^[[:space:]]*(#|$)/ { next }

    /^type [A-Za-z0-9_]+,/ {
        sub(/^[[:space:]]*type[[:space:]]+/, "")
        sub(/;.*/, "")
        split($0, parts, ",")
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", parts[1])
        name = parts[1]
        print "(type " name ")"
        for (i = 2; i <= length(parts); i++) {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", parts[i])
            if (parts[i] != "") print "(typeattribute " name " " parts[i] ")"
        }
        next
    }

    /^init_daemon_domain\(/ {
        sub(/init_daemon_domain\(/, "")
        sub(/\);.*/, "")
        n = $0
        print "(type " n "_exec)"
        print "(typeattribute " n "_exec exec_type)"
        print "(typeattribute " n "_exec vendor_file_type)"
        print "(typeattribute " n "_exec file_type)"
        print "(allow " n " " n "_exec (file (entrypoint execute read open getattr)))"
        print "(typeattribute " n " coredomain)"
        next
    }

    /^vendor_init_daemon_domain\(/ {
        sub(/vendor_init_daemon_domain\(/, "")
        sub(/\);.*/, "")
        n = $0
        print "(allow vendor_init " n "_exec (file (read open getattr execute)))"
        print "(allow vendor_init " n " (process (transition)))"
        next
    }

    /^(get_prop|set_prop)\(/ {
        kind = $0; sub(/\(.*/, "", kind)
        rest = $0; sub(/^[^(]*\(/, "", rest); sub(/\);.*/, "", rest)
        split(rest, sp, ",")
        subj = sp[1]; prop = sp[2]
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", subj)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", prop)
        perms = (kind == "get_prop") ? "read open map" : "read write open map"
        print "(allow " subj " " prop " (property (" perms ")))"
        next
    }

    /^allow [A-Za-z0-9_]+ / {
        s = $2
        t = $3; sub(/:.*/, "", t)
        c = $3; sub(/^[^:]*:/, "", c); sub(/[[:space:]]*\{.*/, "", c)
        p = $0; sub(/.*\{[[:space:]]*/, "", p); sub(/[[:space:]]*\};.*/, "", p)
        gsub(/[[:space:]]+/, " ", p)
        print "(allow " s " " t " (" c " (" p ")))"
        next
    }
' "$TE" > "$TMP"

# --- FC: strip blank lines below comment blocks ---
awk '
    /^#/ { in_comment=1; print; next }
    in_comment && $0 == "" { in_comment=0; next }
    { in_comment=0; print }
' "$FC_IN" > "$FC_TMP"

# --- PC: same ---
awk '
    /^#/ { in_comment=1; print; next }
    in_comment && $0 == "" { in_comment=0; next }
    { in_comment=0; print }
' "$PC_IN" > "$PC_TMP"

# --- ensure target ends with single newline (no trailing blanks) ---
ensure_newline() {
    local f="$1"
    awk 'BEGIN{n=0} {lines[++n]=$0} END{
        while (n>0 && lines[n]=="") n--
        for (i=1;i<=n;i++) print lines[i]
    }' "$f" > "$f.tmp"
    mv "$f.tmp" "$f"
    if [ -n "$(tail -c 1 "$f")" ]; then printf "\n" >> "$f"; fi
}

ensure_newline "$CIL"
{ printf "\n"; cat "$TMP"; } >> "$CIL"
echo "  appended -> $CIL"

ensure_newline "$FC"
{ cat "$FC_TMP"; } >> "$FC"
echo "  appended -> $FC"

ensure_newline "$PC"
{ cat "$PC_TMP"; } >> "$PC"
echo "  appended -> $PC"

rm -f "$TMP" "$FC_TMP" "$PC_TMP"

echo ""
echo "Done. Backups: $CIL.orig $FC.orig $PC.orig"
echo "Push: adb push $CIL $FC $PC /vendor/etc/selinux/"
echo "      adb shell restorecon -R /vendor/etc/selinux/ && reboot"
