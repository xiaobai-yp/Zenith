# ROM Integration — ZenithThermal Daemon

## Overview

ZenithThermal consists of two parts:
1. **ZenithThermal APK** — Kotlin app (UI, profiles management)
2. **zenithd** — Native C daemon (sysfs thermal control, app detection)

This guide covers embedding `zenithd` + config into an AOSP-based ROM.

---

## Directory Structure in ROM (vendor)

```
/vendor/
├── bin/
│   └── zenithd                          # daemon binary
├── etc/
│   ├── init/
│   │   └── init.zenith.rc               # init service definition
│   ├── profiles.json                    # per-app profile mappings
│   └── thermal_zones.json               # thermal zone config
│   └── selinux/
│       ├── zenithd.te                   # SELinux policy
│       ├── property_contexts            # property contexts (append)
│       └── file_contexts               # file contexts (append)
```

---

## Build with AOSP (Soong)

Add to your `device.mk` (or equivalent):

```makefile
# ZenithThermal daemon
PRODUCT_PACKAGES += zenithd

# Config files
PRODUCT_COPY_FILES += \
    vendor/zenith/daemon/config/profiles.json:$(TARGET_COPY_OUT_VENDOR)/etc/profiles.json \
    vendor/zenith/daemon/config/thermal_zones.json:$(TARGET_COPY_OUT_VENDOR)/etc/thermal_zones.json \
    vendor/zenith/daemon/init.zenith.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.zenith.rc

# SELinux policy
BOARD_SEPOLICY_DIRS += vendor/zenith/daemon/sepolicy
```

The `Android.bp` at `native/Android.bp` defines both `zenithd` (binary) and `libzenith` (shared lib).

---

## SELinux Integration

Append the contents of:
- `zenithd.te` — type definitions and access rules
- `file_contexts` — file labels for socket, binary, config dirs
- `property_contexts` — property labels for `persist.sys.zenith.*`

These must be merged into your ROM's SELinux policy. The daemon needs:
- `sysfs_thermal`, `sysfs_devices_system_cpu*` read/write
- `proc_pid` read for `/proc/*/oom_score_adj`
- `kernel:system syslog_read`
- Socket IPC with the APK (via `zenithd_socket` label)

> **Note:** Some sysfs types (`sysfs_kgsl`, `sysfs_thermal_message`) are vendor-specific.
> Test on device and adjust policy to match your kernel's actual labels.

---

## Property Trigger Logic

When the APK sets `persist.sys.zenith.thermal=<profile_id>`, init.rc triggers write sysfs values (governor, I/O scheduler, TCP, GPU, sched_bore, rate_limit, min/max freq).

**Priority:**
1. If `init.zenith.rc` has a trigger for that profile ID → init applies kernel settings
2. The daemon's `profile_engine` applies CPU freq cap directly from `profiles.json`

This two-layer system means:
- init.rc handles infrastructure (governor, scheduler, TCP — things that need root-level write)
- profiles.json handles fine-tune CPU freq caps (per-app tuning)

---

## Running the Daemon

After flashing, the daemon starts automatically at `late_start` (from `init.zenith.rc`).

Manual control:
```bash
# Stop
stop zenithd

# Start
start zenithd

# Set profile manually
setprop persist.sys.zenith.thermal 10

# Check logs
logcat -s ZenithD
```

---

## Profile IDs

| ID | Mode | Governor | I/O | TCP |
|----|------|----------|-----|-----|
| 0  | Default | schedutil | bfq | westwood |
| 2  | Powersave | powersave | - | - |
| 8  | In-Call | schedutil | bfq | westwood |
| 9  | Game | vorpal | ssg | bbrplus |
| 10 | Dynamic | vorpal | ssg | bbrplus |
| 11 | Class 0 | schedutil | bfq | westwood |
| 12 | Camera | schedutil | bfq | westwood |
| 13 | PUBG | vorpal | ssg | bbrplus |
| 14 | YouTube | schedutil | bfq | westwood |
| 15 | AR/VR | schedutil | bfq | westwood |
| 16 | Game 2 | vorpal | ssg | bbrplus |
