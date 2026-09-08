#!/system/bin/sh
# inject_zenithd_policy.sh — Append zenithd SELinux rules to vendor_sepolicy.cil.
# Run as root (adb shell) on device.
# WARNING: Backup + wrong syntax = bootloop. Always keep recovery available.

set -e

CIL="/vendor/etc/selinux/vendor_sepolicy.cil"
BACKUP="/data/local/zenithd_policy_backup.cil"
RULES="/vendor/etc/zenith_sepolicy.cil"

if [ ! -f "$CIL" ]; then
    echo "ERROR: $CIL not found"
    exit 1
fi

# Step 1: Backup
cp "$CIL" "$BACKUP"
echo "Backup saved to $BACKUP"

# Step 2: Write CIL rules
cat > "$RULES" << 'EOF'
(type zenithd)
(typeattribute zenithd domain)
(type zenithd_exec)
(type zenithd_socket)

(allow zenithd sysfs (dir (search)))
(allow zenithd sysfs (file (read write open)))
(allow zenithd sysfs_devices_system_cpu (dir (search)))
(allow zenithd sysfs_devices_system_cpu (file (read write open)))
(allow zenithd sysfs_devices_system_cpu_cpu (dir (search)))
(allow zenithd sysfs_devices_system_cpu_cpu (file (read write open)))
(allow zenithd sysfs_thermal (file (read write open)))
(allow zenithd sysfs_thermal_message (file (read write open)))
(allow zenithd sysfs_kgsl (file (read write open)))
(allow zenithd sysfs_block (file (read write open)))
(allow zenithd proc (dir (search)))
(allow zenithd proc (file (read open)))
(allow zenithd proc_pid (dir (search)))
(allow zenithd proc_pid (file (read open)))
(allow zenithd proc_pid_cmdline (file (read open)))
(allow zenithd proc_pid_status (file (read open)))
(allow zenithd kernel (system (syslog_read)))
(allow zenithd logdr_socket (sock_file (write)))
(allow zenithd logd (unix_stream_socket (connectto read write)))
(allow zenithd zenithd_socket (sock_file (create write unlink)))
(allow zenithd zenithd (unix_stream_socket (create bind listen accept connectto read write)))
(allow zenithd vendor_etc_zenith (file (read open)))
(allow zenithd zenithd_data_file (dir (search)))
(allow zenithd zenithd_data_file (file (read open create write unlink)))
EOF
echo "Rules written to $RULES"

# Step 3: Mount rw
mount -o rw,remount /vendor 2>/dev/null || mount -o rw,remount /

# Step 4: Append to vendor_sepolicy.cil
cat "$RULES" >> "$CIL"
echo "Injected to $CIL"

# Step 5: Verify not empty
if [ ! -s "$CIL" ]; then
    echo "ERROR: CIL file is empty! Restoring backup."
    cp "$BACKUP" "$CIL"
    exit 1
fi

# Step 6: Restorecon
restorecon -R /vendor/etc/selinux/ 2>/dev/null

# Step 7: Remount ro
mount -o ro,remount /vendor 2>/dev/null || mount -o ro,remount /

echo "Done. Reboot now:"
echo "  reboot"
