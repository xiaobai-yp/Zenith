# ZenithThermal

Per-app + global thermal profile manager for Android. Runs a Rust daemon via `su`, applies thermal/governor/freq settings via `init.rc` property triggers.

## Architecture

```
┌──────────────┐  setprop  ┌──────────────┐  sysfs  ┌──────────┐
│  Kotlin App  │ ────────► │   init.rc    │ ──────► │ Hardware │
│  (UI + IPC)  │           │  (triggers)  │         │ sconfig  │
│              │◄─────────│              │         │ governor │
│              │  LocalSocket           │         │ freq/GPU │
│              │           └──────────────┘         │ I/O/TCP  │
└──────┬───────┘                                   └──────────┘
       │ su + exec
       ▼
┌──────────────┐  detect_fg → setprop + sconfig
│   zenithd    │  (Rust daemon)
│  app_detect  │
│  battery_mon │
│  ipc_server  │
└──────────────┘
```

- **Daemon** = `setprop` + `sconfig` writes only. All hardware config (governor, freq, GPU, I/O, TCP) via `init.rc` property triggers.
- **App** = `setprop` + daemon IPC. No direct sysfs writes.
- **init.rc** = single hardware writer. No conflicts.

## Features

- **PerApp Thermal** — different thermal profiles per app (auto-switch on foreground change)
- **Global Profile** — system-wide thermal preset (Default, Powersave, Dynamic, Game, etc.)
- **Launcher Transient** — launcher/recents skipped, thermal stays stable during navigation
- **Battery Monitor** — persistent battery stats, idle drain, deep sleep, charge tracking
- **Root via su** — no KSU module, no SELinux, no system partition

## Profiles

| ID | Mode | Governor | I/O | sched_bore |
|----|------|----------|-----|------------|
| 0 | Default | schedutil | bfq | 0 |
| 8 | In-Call | powersave | - | - |
| 9 | Game | vorpal | none | 1 |
| 10 | Dynamic | vorpal | none | 1 |
| 11 | Class 0 | schedutil | bfq | 0 |
| 12 | Camera | schedutil | bfq | 0 |
| 13 | PUBG | vorpal | none | 1 |
| 14 | YouTube | schedutil | bfq | 0 |
| 15 | AR/VR | schedutil | bfq | 0 |
| 16 | Game 2 | vorpal | none | 1 |

## Build

### Daemon (Rust)
CI builds via GitHub Actions (`.github/workflows/build.yml`). No local `cargo` needed.
Binary is committed to `app/src/main/assets/zenithd`.

### App (Kotlin)
CI builds via GitHub Actions (`.github/workflows/build-app.yml`).

## Install

1. APK installs normally (`adb install` or file manager)
2. App requests root on first launch
3. Daemon starts via `su`, listens on LocalSocket
4. `init.rc` triggers handle hardware config

## Requirements

- Android 10+ (tested on Android 16, OPPO CPH2731IN)
- Root access (su)
- `init.rc` config for hardware triggers (see `daemon/init.zenith.rc`)

## Project Structure

```
app/src/main/java/com/zenith/thermal/
  MainActivity.kt          — UI, profile selection, PerApp config
  ThermalController.kt     — setprop + IPC bridge
  DaemonManager.kt         — daemon lifecycle, start/stop
  ZenithDaemonClient.kt    — LocalSocket IPC
  BatteryMonitorService.kt — persistent battery stats
  AppProfileCache.kt       — SharedPreferences local storage

daemon/rust/src/
  main.rs                  — entry, monitor loop, IPC server
  app_monitor.rs           — foreground detection (oom_score_adj)
  thermal_core.rs          — sconfig writes
  profile_engine.rs        — per-app profile lookup
  battery_monitor.rs       — battery stats accumulation
  fps_monitor.rs           — FPS monitoring
  benchmark.rs             — CPU benchmark
  sysfs_monitor.rs         — sysfs node discovery

daemon/
  init.zenith.rc           — property triggers for hardware config
```

## License

Private — xiaobai-yp
