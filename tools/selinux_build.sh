#!/bin/bash
# selinux_build.sh — Build ZenithThermal SELinux policy locally.
#
# Put THIS script + 3 source files in ONE folder, then run it:
#   file_contexts       (zenithd file context entries)
#   property_contexts   (zenithd property context entries)
#   zenithd.te          (zenithd .te policy source)
#
# Output (written in this same folder):
#   vendor_file_contexts
#   vendor_property_contexts
#   vendor_sepolicy.cil
#
# Nothing is pushed to /vendor — you copy manually.
#
# Copyright (c) 2026 ZenithThermal Contributors
# SPDX-License-Identifier: MIT

set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

echo "=== Zenith SELinux build ==="
echo "Folder: $DIR"

# --- Inputs must exist ---
[ -f zenithd.te ]       || { echo "ERROR: zenithd.te not in this folder"; exit 1; }
[ -f file_contexts ]    || { echo "ERROR: file_contexts not in this folder"; exit 1; }
[ -f property_contexts ]|| { echo "ERROR: property_contexts not in this folder"; exit 1; }

PREFIX="vendor_"
TE="zenithd.te"
FC="file_contexts"
PC="property_contexts"

echo "Reading $TE, $FC, $PC ..."

# --- 1) file_contexts -> vendor_file_contexts (verbatim copy) ---
cp "$FC" "${PREFIX}file_contexts"
cp "${PREFIX}file_contexts" "${PREFIX}file_contexts.bak"
echo "  wrote vendor_file_contexts + vendor_file_contexts.bak ($(wc -l < "${PREFIX}file_contexts") lines)"

# --- 2) property_contexts -> vendor_property_contexts (verbatim copy) ---
cp "$PC" "${PREFIX}property_contexts"
cp "${PREFIX}property_contexts" "${PREFIX}property_contexts.bak"
echo "  wrote vendor_property_contexts + vendor_property_contexts.bak ($(wc -l < "${PREFIX}property_contexts") lines)"

# --- 3) zenithd.te -> vendor_sepolicy.cil (convert .te to CIL) ---
python3 - "$TE" "${PREFIX}sepolicy.cil" <<'PYEOF'
import sys, re

TE, OUT = sys.argv[1], sys.argv[2]

def die(msg):
    print("ERROR:", msg)
    sys.exit(1)

try:
    lines = open(TE).read().splitlines()
except OSError:
    die(f"cannot read {TE}")

cil = []
exec_seen = False

def add(x): cil.append(x)

for raw in lines:
    line = raw.strip()
    if not line or line.startswith('#'):
        continue

    # type decl: "type zenithd, domain;"
    m = re.fullmatch(r'type\s+(\w+)\s*,\s*([\w\s,]+);', line)
    if m:
        name = m.group(1)
        attrs = [a for a in m.group(2).split(',') if a.strip()]
        add(f"(type {name})")
        for a in attrs:
            add(f"(typeattribute {name} {a.strip()})")
        continue

    # macros
    if re.fullmatch(r'init_daemon_domain\((\w+)\)', line):
        n = re.fullmatch(r'init_daemon_domain\((\w+)\)', line).group(1)
        if not exec_seen:
            add(f";; init_daemon_domain({n})")
            add(f"(type {n}_exec)")
            add(f"(typeattribute {n}_exec exec_type)")
            add(f"(typeattribute {n}_exec vendor_file_type)")
            add(f"(typeattribute {n}_exec file_type)")
            add(f"(allow {n} {n}_exec (file (entrypoint execute read open getattr)))")
            exec_seen = True
        add(f"(typeattribute {n} coredomain)")
        continue

    if re.fullmatch(r'vendor_init_daemon_domain\((\w+)\)', line):
        n = re.fullmatch(r'vendor_init_daemon_domain\((\w+)\)', line).group(1)
        add(f";; vendor_init_daemon_domain({n})")
        add(f"(allow vendor_init {n}_exec (file (read open getattr execute)))")
        add(f"(allow vendor_init {n} (process (transition)))")
        continue

    # get_prop / set_prop
    m = re.fullmatch(r'(get_prop|set_prop)\((\w+),\s*(\w+)\)', line)
    if m:
        kind, subj, prop = m.groups()
        perms = "read open map" if kind == "get_prop" else "read write open map"
        add(f"(allow {subj} {prop} (property ({perms})))")
        continue

    # allow rule
    m = re.fullmatch(r'allow\s+(\w+)\s+(\w+):(\w+)\s*\{([^}]*)\}\s*;', line)
    if m:
        s, t, c, p = m.groups()
        add(f"(allow {s} {t} ({c} ({' '.join(p.split())})))")
        continue

    # unknown line -> skip

if not cil:
    die("no usable CIL rules found in " + TE)

cil = ["", ";; ZenithThermal SELinux policy (auto-generated from " + TE + ")", ""] + cil

with open(OUT, "w") as f:
    f.write("\n".join(cil) + "\n")

# backup the new file
import shutil
shutil.copy2(OUT, OUT + ".bak")

print(f"  wrote vendor_sepolicy.cil + vendor_sepolicy.cil.bak ({len(cil)} lines)")
PYEOF

echo ""
echo "=== Done ==="
echo "Output in this folder:"
ls -1 vendor_*
echo ""
echo "Copy to device:"
echo "  adb push vendor_sepolicy.cil      /vendor/etc/selinux/vendor_sepolicy.cil"
echo "  adb push vendor_file_contexts     /vendor/etc/selinux/vendor_file_contexts"
echo "  adb push vendor_property_contexts /vendor/etc/selinux/vendor_property_contexts"
echo "  adb shell restorecon -R /vendor/etc/selinux/"
echo "  adb reboot"