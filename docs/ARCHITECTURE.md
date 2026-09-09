# ZenithThermal Native Daemon Architecture

## Overview

ZenithThermal is a native daemon (`zenithd`) for Android custom ROMs that manages
CPU/GPU thermal profiles, monitors battery drain, and provides IPC for the Kotlin
UI app.

## Components

```
┌─────────────────────────────────────────────┐
│  Kotlin UI App (com.zenith.thermal)         │
│  ↕ IPC via /dev/socket/zenithd              │
├─────────────────────────────────────────────┤
│  zenithd (C daemon)                         │
│  ├── socket_server  — UNIX socket IPC       │
│  ├── app_monitor    — /proc scanning        │
│  ├── sysfs_monitor  — thermal/CPU/battery   │
│  ├── battery_monitor— drain rate calc       │
│  ├── thermal_core   — governor/freq control │
│  ├── profile_engine — profiles.json loader  │
│  ├── crypto         — ROM verify + HMAC     │
│  └── daemon.h       — shared types          │
├─────────────────────────────────────────────┤
│  init.zenith.rc — property triggers         │
│  SELinux policy — zenithd.te + contexts     │
└─────────────────────────────────────────────┘
```

## Data Flow

1. **App detection**: `app_monitor` scans `/proc/*/oom_score_adj` to find the
   foreground app package name.

2. **Profile lookup**: `profile_engine` maps package → profile_id using a
   configurable mapping table. Falls back to default profile (0).

3. **Profile application**: `thermal_core` writes governor and frequency limits
   to `/sys/devices/system/cpu/*/cpufreq/scaling_*`.

4. **Monitoring**: `sysfs_monitor` reads thermal zones, battery info, and CPU
   state. `battery_monitor` computes drain rates from a circular sample buffer.

5. **IPC**: `socket_server` handles the wire protocol (CRC32-validated packets)
   for the Kotlin UI to query status, set profiles, and manage thermal limits.

## Profile Priority

1. `init.rc` property triggers (`persist.sys.zenith.thermal=N`) for fast boot-time
   application.
2. `zenithd` daemon applies profiles.json when the foreground app changes.
3. Kotlin UI can override via `MSG_SET_PROFILE` IPC message.

## Profiles (profiles.json)

11 profiles (IDs: 0, 2, 8, 9, 10, 11, 12, 13, 14, 15, 16) with per-cluster
CPU frequency and governor settings:

| Profile | Name        | Governor   | CPU7 Max    |
|---------|-------------|------------|-------------|
| 0       | Default     | schedutil  | 2,841,600   |
| 2       | PowerSave   | powersave  | 1,862,400   |
| 8       | In-Call     | schedutil  | 2,841,600   |
| 9       | Game        | schedutil  | 3,187,200   |
| 10      | Dynamic     | schedutil  | 2,841,600   |
| 13      | PUBG        | schedutil  | 3,187,200   |
| 16      | Game 2      | schedutil  | 2,745,600   |

## Security

- String encryption via `string_enc.h` XOR macros defeat `strings(1)`.
- ROM fingerprint verification binds daemon to the ROM.
- HMAC challenge/response for IPC authentication.
- Anti-debug via `TracerPid` check.
- SELinux policy restricts sysfs/property access.

## Build

- NDK CMake build targeting `arm64-v8a` (API 26+).
- Produces `zenithd` binary and `libzenith.so` shared library.
- See [BUILD.md](BUILD.md) for build instructions.
