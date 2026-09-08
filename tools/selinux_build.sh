#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

CIL="vendor_sepolicy.cil"
FC="vendor_file_contexts"
PC="vendor_property_contexts"
TE="zenithd.te"
FC_IN="file_contexts"
PC_IN="property_contexts"
TMP="$DIR/.zenith_cil.tmp"
FC_TMP="$DIR/.zenith_fc.tmp"
PC_TMP="$DIR/.zenith_pc.tmp"

echo "=== Zenith SELinux append ==="
echo "Folder: $DIR"

[ -f "$TE" ]    || { echo "ERROR: $TE not found"; exit 1; }
[ -f "$FC_IN" ] || { echo "ERROR: $FC_IN not found"; exit 1; }
[ -f "$PC_IN" ] || { echo "ERROR: $PC_IN not found"; exit 1; }
[ -f "$CIL" ]   || { echo "ERROR: $CIL not found (pull from device first)"; exit 1; }
[ -f "$FC" ]    || { echo "ERROR: $FC not found (pull from device first)"; exit 1; }
[ -f "$PC" ]    || { echo "ERROR: $PC not found (pull from device first)"; exit 1; }

for f in "$CIL" "$FC" "$PC"; do
    [ -f "$f.orig" ] || { cp "$f" "$f.orig"; echo "Backup: $f.orig"; }
done

# --- 1) zenithd.te -> CIL rules (strip # license, blank only) ---
python3 -c "
import sys, re

lines = open(sys.argv[1]).read().splitlines()
cil = []
exec_seen = False

for raw in lines:
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    m = re.fullmatch(r'type\s+(\w+)\s*,\s*([\w\s,]+);', line)
    if m:
        name = m.group(1)
        attrs = [a.strip() for a in m.group(2).split(',') if a.strip()]
        cil.append('(type %s)' % name)
        for a in attrs:
            cil.append('(typeattribute %s %s)' % (name, a))
        continue
    m2 = re.fullmatch(r'init_daemon_domain\((\w+)\)', line)
    if m2:
        n = m2.group(1)
        if not exec_seen:
            cil.append(';; init_daemon_domain(%s)' % n)
            cil.append('(type %s_exec)' % n)
            cil.append('(typeattribute %s_exec exec_type)' % n)
            cil.append('(typeattribute %s_exec vendor_file_type)' % n)
            cil.append('(typeattribute %s_exec file_type)' % n)
            cil.append('(allow %s %s_exec (file (entrypoint execute read open getattr)))' % (n, n))
            exec_seen = True
        cil.append('(typeattribute %s coredomain)' % n)
        continue
    m3 = re.fullmatch(r'vendor_init_daemon_domain\((\w+)\)', line)
    if m3:
        n = m3.group(1)
        cil.append(';; vendor_init_daemon_domain(%s)' % n)
        cil.append('(allow vendor_init %s_exec (file (read open getattr execute)))' % n)
        cil.append('(allow vendor_init %s (process (transition)))' % n)
        continue
    m4 = re.fullmatch(r'(get_prop|set_prop)\((\w+),\s*(\w+)\)', line)
    if m4:
        kind, subj, prop = m4.groups()
        perms = 'read open map' if kind == 'get_prop' else 'read write open map'
        cil.append('(allow %s %s (property (%s)))' % (subj, prop, perms))
        continue
    m5 = re.fullmatch(r'allow\s+(\w+)\s+(\w+):(\w+)\s*\{([^}]*)\}\s*;', line)
    if m5:
        s, t, c, p = m5.groups()
        cil.append('(allow %s %s (%s (%s)))' % (s, t, c, ' '.join(p.split())))
        continue

with open(sys.argv[2], 'w') as f:
    f.write('\n'.join(cil) + '\n')
print('  converted %d CIL rules' % len(cil))
" "$TE" "$TMP"

# --- 2) FC: strip blank line below license ---
awk '
    /^#/ && license==0 { license=1; print; next }
    license && $0=="" { license=0; next }
    { print }
' "$FC_IN" > "$FC_TMP"

# --- 3) PC: strip blank line below license ---
awk '
    /^#/ && license==0 { license=1; print; next }
    license && $0=="" { license=0; next }
    { print }
' "$PC_IN" > "$PC_TMP"

# helper: ensure target ends with exactly one newline (no extra blank lines)
ensure_newline() {
    local f="$1"
    python3 - "$f" <<'PY'
import sys
p = sys.argv[1]
with open(p, 'rb') as fh:
    data = fh.read()
# remove trailing blank lines
while data.endswith(b'\n\n'):
    data = data[:-1]
# ensure single trailing newline
if not data.endswith(b'\n'):
    data += b'\n'
with open(p, 'wb') as fh:
    fh.write(data)
PY
}

# CIL: append blank + rules (no license)
ensure_newline "$CIL"
{ printf "\n"; cat "$TMP"; } >> "$CIL"
echo "  appended -> $CIL"

# FC: append block directly after vendor content (no extra blank above license)
ensure_newline "$FC"
{ cat "$FC_TMP"; } >> "$FC"
echo "  appended -> $FC"

# PC: same
ensure_newline "$PC"
{ cat "$PC_TMP"; } >> "$PC"
echo "  appended -> $PC"

rm -f "$TMP" "$FC_TMP" "$PC_TMP"

echo ""
echo "=== Done ==="
echo "Backups: $CIL.orig  $FC.orig  $PC.orig"
echo ""
echo "Push back:"
echo "  adb push $CIL /vendor/etc/selinux/vendor_sepolicy.cil"
echo "  adb push $FC  /vendor/etc/selinux/vendor_file_contexts"
echo "  adb push $PC  /vendor/etc/selinux/vendor_property_contexts"
echo "  adb shell restorecon -R /vendor/etc/selinux/"
echo "  adb reboot"
