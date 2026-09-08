#!/bin/bash
# build_zenith_selinux.sh — Local merge. Put this + zenithd.te + file_contexts
# + property_contexts in ONE folder. Run. Output in SAME folder. No vendor push.
#
# Input:
#   zenithd.te           ← SELinux source (.te format)
#   file_contexts        ← zenithd file context entries
#   property_contexts    ← zenithd property context entries
#
# Output:
#   vendor_sepolicy.cil       ← full CIL policy (append to /vendor/etc/selinux/)
#   vendor_file_contexts      ← merged file contexts
#   vendor_property_contexts  ← merged property contexts
#   *.bak                     ← backups of originals
#
# Copyright (c) 2026 ZenithThermal Contributors
# SPDX-License-Identifier: MIT

set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

echo "=== Zenith SELinux local build ==="
echo "Folder: $DIR"

[ -f zenithd.te ]       || { echo "ERROR: zenithd.te not found"; exit 1; }
[ -f file_contexts ]    || { echo "ERROR: file_contexts not found"; exit 1; }
[ -f property_contexts ]|| { echo "ERROR: property_contexts not found"; exit 1; }

# --- Backup originals ---
cp zenithd.te zenithd.te.bak
cp file_contexts file_contexts.bak
cp property_contexts property_contexts.bak
echo "Backups: zenithd.te.bak file_contexts.bak property_contexts.bak"

# --- Convert .te to CIL ---
echo ""
echo "=== Converting zenithd.te -> vendor_sepolicy.cil ==="

python3 - "$DIR" <<'PYEOF'
import sys, re

def convert_te_to_cil(te_path):
    with open(te_path) as f:
        lines = f.readlines()

    cil = ["", ";; ZenithThermal SELinux policy (auto-generated)", ""]

    # Collect type declarations and allow rules
    type_decls = []
    allow_rules = []
    macro_rules = []
    exec_seen = False

    for line in lines:
        line = line.strip()
        if not line or line.startswith('#'):
            continue

        # type declaration: "type zenithd, domain;"
        m = re.match(r'^type\s+(\w+)\s*,\s*([\w\s,]+);', line)
        if m:
            type_name = m.group(1)
            attrs = [a.strip() for a in m.group(2).split(',')]
            cil.append(f"(type {type_name})")
            for attr in attrs:
                cil.append(f"(typeattribute {type_name} {attr})")
            type_decls.append(type_name)
            continue

        # init_daemon_domain macro -> expand
        if 'init_daemon_domain(' in line:
            proc_name = re.search(r'init_daemon_domain\((\w+)\)', line)
            if proc_name:
                n = proc_name.group(1)
                # Ensure no duplicate type decl for exec
                if not exec_seen:
                    cil.append(f";; init_daemon_domain({n})")
                    cil.append(f"(type {n}_exec)")
                    cil.append(f"(typeattribute {n}_exec exec_type)")
                    cil.append(f"(typeattribute {n}_exec vendor_file_type)")
                    cil.append(f"(typeattribute {n}_exec file_type)")
                    cil.append(f"(allow {n} {n}_exec (file (entrypoint)))")
                    cil.append(f"(allow {n} {n}_exec (file (execute read open getattr)))")
                    exec_seen = True
                cil.append(f"(typeattribute {n} coredomain)")
            continue

        # vendor_init_daemon_domain macro -> expand
        if 'vendor_init_daemon_domain(' in line:
            proc_name = re.search(r'vendor_init_daemon_domain\((\w+)\)', line)
            if proc_name:
                n = proc_name.group(1)
                cil.append(f";; vendor_init_daemon_domain({n})")
                cil.append(f"(allow vendor_init {n}_exec (file (read open getattr execute)))")
                cil.append(f"(allow vendor_init {n} (process (transition)))")
            continue

        # set_prop / get_prop macros
        m = re.match(r'(set_prop|get_prop)\((\w+),\s*(\w+)\)', line)
        if m:
            macro, subj, prop = m.groups()
            perms = "read open map" if macro == "get_prop" else "read write open map"
            cil.append(f"(allow {subj} {prop} (property ({perms})))")
            macro_rules.append(line)
            continue

        # allow rule: "allow source target:class { perms };"
        m = re.match(r'^allow\s+(\w+)\s+(\w+):(\w+)\s*\{([^}]*)\}\s*;', line)
        if m:
            src, tgt, cls, perms = m.groups()
            perm_list = ' '.join(perms.strip().split())
            cil.append(f"(allow {src} {tgt} ({cls} ({perm_list})))")
            allow_rules.append(line)
            continue

    # File contexts
    cil.append("")
    cil.append(";; Zenith file context entries")
    cil.append("(filecon \"/vendor/bin/zenithd\"  file (u:object_r:zenithd_exec:s0))")
    cil.append("(filecon \"/dev/socket/zenithd\"  socket (u:object_r:zenithd_socket:s0))")
    cil.append("(filecon \"/vendor/etc/profiles.json\"  file (u:object_r:vendor_etc_zenith:s0))")
    cil.append("(filecon \"/vendor/etc/thermal_zones.json\"  file (u:object_r:vendor_etc_zenith:s0))")
    cil.append("(filecon \"/data/local/zenith\"  dir (u:object_r:zenithd_data_file:s0))")
    cil.append("(filecon \"/data/local/zenith(/.*)?\"  any (u:object_r:zenithd_data_file:s0))")

    # Property contexts
    cil.append("")
    cil.append(";; Zenith property context entries")
    cil.append("(propertycon \"persist.sys.zenith.thermal\" (u:object_r:system_prop:s0))")
    cil.append("(propertycon \"persist.sys.zenith.profile\" (u:object_r:system_prop:s0))")
    cil.append("(propertycon \"zenith.thermal.profile\" (u:object_r:system_prop:s0))")
    cil.append("(propertycon \"zenith.app.pkg\" (u:object_r:system_prop:s0))")

    cil.append("")
    return '\n'.join(cil)

input_file = sys.argv[1] + '/zenithd.te'
output_file = sys.argv[1] + '/vendor_sepolicy.cil'

cil_content = convert_te_to_cil(input_file)
with open(output_file, 'w') as f:
    f.write(cil_content)

lines = cil_content.count('\n')
print(f"Wrote vendor_sepolicy.cil ({lines} lines)")
PYEOF

# --- Copy file contexts (already in correct format) ---
cp file_contexts vendor_file_contexts
echo "Wrote vendor_file_contexts ($(wc -l < vendor_file_contexts) lines)"

# --- Copy property contexts ---
cp property_contexts vendor_property_contexts
echo "Wrote vendor_property_contexts ($(wc -l < vendor_property_contexts) lines)"

echo ""
echo "=== Done ==="
echo ""
echo "Output in $DIR:"
ls -1 vendor_* zenithd.te.bak file_contexts.bak property_contexts.bak
echo ""
echo "Copy to device:"
echo "  adb push vendor_sepolicy.cil       /vendor/etc/selinux/vendor_sepolicy.cil"
echo "  adb push vendor_file_contexts      /vendor/etc/selinux/vendor_file_contexts"
echo "  adb push vendor_property_contexts  /vendor/etc/selinux/vendor_property_contexts"
echo "  adb shell restorecon -R /vendor/etc/selinux/"
echo "  adb reboot"
echo ""
echo "Recovery: replace vendor files with .bak originals"