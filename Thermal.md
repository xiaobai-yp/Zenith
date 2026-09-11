# Thermal System — Technical Reference

**File:** `init.custom.thermal.rc`  
**Property:** `persist.sys.zenith.thermal`  
**Trigger mechanism:** Android `init` property actions  
**Target hardware:** Snapdragon 7 Gen 1 (sm7475) — 3-cluster CPU (policy0 / policy4 / policy7), Adreno 644L GPU

---

## 1. How It Works

`ThermalController.apply(thermalId: Int)` calls:

```kotlin
SystemProperties.set("persist.sys.zenith.thermal", thermalId.toString())
```

`init` monitors this property. When it changes, the matching `on property:persist.sys.zenith.thermal=<id>` block in `init.custom.thermal.rc` executes synchronously, writing to sysfs nodes and procfs entries. This is a zero-latency, no-root-at-runtime mechanism — the only root dependency is setting the initial `chmod 0664/0666` on boot.

The fallback at the end of the RC file (`on property:persist.sys.zenith.thermal=*`) passes the raw value directly to `sconfig`, allowing the thermal HAL to handle any ID not explicitly defined in the RC.

---

## 2. Boot Initialization

On `on boot`, the following nodes are opened to non-root writes (`chmod 0664` or `0666`):

**CPU governors and frequency limits (3 clusters):**
- `policy0`: Little cores (cpu0–cpu3)
- `policy4`: Mid cores (cpu4–cpu6)
- `policy7`: Prime core (cpu7)

Nodes unlocked per cluster: `scaling_governor`, `scaling_max_freq`, `scaling_min_freq`

**GPU:**
- `/sys/class/kgsl/kgsl-3d0/devfreq/governor`
- `/sys/class/kgsl/kgsl-3d0/max_clock_mhz`
- `/sys/class/kgsl/kgsl-3d0/devfreq/min_freq`
- `/sys/class/kgsl/kgsl-3d0/default_pwrlevel`

**I/O schedulers:** `sda` through `sdh`, `mmcblk0`, `mmcblk1`

**Schedutil / Vorpal rate limiters:** `rate_limit_us` for all 3 policies (both governor variants)

**Thermal HAL interface:** `/sys/class/thermal/thermal_message/sconfig` (0666)

---

## 3. Profile Reference Table

| Profile Name | App ID | Thermal ID (`sconfig`) | CPU Governor | I/O Scheduler | `sched_bore` | TCP CC | GPU Max Clock |
|---|---|---|---|---|---|---|---|
| Default | 0 | 0 | schedutil | bfq | 0 | westwood | (ROM default) |
| Dynamic | 1 | 10 | vorpal | ssg | 1 | bbrplus | 592 MHz |
| In-Calls | 2 | 8 | schedutil | bfq | 0 | westwood | (ROM default) |
| Game | 3 | 9 | vorpal | ssg | 1 | bbrplus | 592 MHz |
| Game 2 | 4 | 16 | vorpal | ssg | 1 | bbrplus | 592 MHz |
| Pubg | 5 | 13 | vorpal | ssg | 1 | bbrplus | 592 MHz |
| AR & VR | 6 | 15 | schedutil | bfq | 0 | westwood | (ROM default) |
| Class 0 | 7 | 11 | schedutil | bfq | 0 | westwood | (ROM default) |
| Camera | 8 | 12 | schedutil | bfq | 0 | westwood | (ROM default) |
| YouTube | 9 | 14 | schedutil | bfq | 0 | westwood | (ROM default) |

**App ID** = index in `Profile.VALUES` / `Profile.NAMES` arrays (what `ProfileStore` saves).  
**Thermal ID** = the actual value written to `persist.sys.zenith.thermal` and `sconfig`.

---

## 4. Profile Deep-Dive

### 4.1 Default (ID 0 → sconfig 0)

**Intent:** Balanced everyday use. Lets the thermal HAL manage performance autonomously.

**CPU:** `schedutil` on all clusters — frequency scales with CPU utilization, respecting thermal HAL limits. Rate limiters: 2 ms (policy0), 3 ms (policy4), 5 ms (policy7) — slower prime core response reduces power spikes.

**GPU:** `default_pwrlevel 3` — mid-level GPU power state, HAL-managed.

**I/O:** `bfq` (Budget Fair Queueing) — low-latency, fair scheduling, good for interactive mixed workloads.

**Network:** `westwood` TCP congestion control — conservative, well-suited for lossy mobile networks.

**sched_bore:** Off — standard CFS task scheduling.

---

### 4.2 Dynamic (ID 1 → sconfig 10)

**Intent:** Adaptive high-performance mode. Named "Dynamic (evaluation)" in the menu — suggests it is still being characterized.

**CPU:** `vorpal` governor on all clusters — a custom governor tuned for gaming workloads with `gaming_mode 1` enabled on cpu7 (prime core). Rate limiters set to 2 ms uniform across all clusters for faster frequency response.

**GPU:** `max_clock_mhz 592`, `max_pwrlevel 0` — unlocks the GPU to its highest sustained clock. `max_pwrlevel 0` = highest performance tier.

**I/O:** `ssg` (Samsung Storage Governor or equivalent) — optimized for sequential/burst workloads, lower queue depth pressure.

**Network:** `bbrplus` TCP — bandwidth-probe model, better throughput on high-RTT connections.

**sched_bore:** On — Burst-Oriented Response Enhancement; gives recently-awakened tasks a temporary boost, reducing latency in burst-heavy workloads.

**Difference from Game:** The sconfig value (10 vs 9) differs — the thermal HAL may apply different skin-temperature throttle curves. Kernel behavior is otherwise identical to Game.

---

### 4.3 In-Calls (ID 2 → sconfig 8)

**Intent:** Audio-first profile. Minimize CPU frequency jitter to reduce audio glitches.

**CPU:** `schedutil`, default min freqs. No max freq cap is explicitly set, so the thermal HAL's ceiling applies.

**I/O:** `bfq` — predictable latency.

**Network:** `westwood` — stable, avoids congestion window explosions.

**sched_bore:** Off.

**No GPU override** — GPU remains under HAL control.

---

### 4.4 Game (ID 3 → sconfig 9)

**Intent:** Primary gaming profile. Identical kernel tuning to Dynamic.

`sconfig 9` vs `sconfig 10` is the only difference — the thermal HAL interprets these IDs to apply different temperature throttle policies. Game (sconfig 9) likely has a more aggressive throttle curve than Dynamic (sconfig 10) to control sustained skin temperature during long sessions.

**All other settings:** Identical to Dynamic — vorpal + gaming_mode, ssg, bbrplus, sched_bore on, GPU 592 MHz.

---

### 4.5 Game 2 (ID 4 → sconfig 16)

**Intent:** Alternative gaming profile — possibly for titles that behave differently under HAL game mode signals (e.g., titles that internally request `sconfig 16` from the HAL SDK).

Kernel tuning: identical to Game and Dynamic.

---

### 4.6 Pubg (ID 5 → sconfig 13)

**Intent:** Game-class profile tuned for PUBG Mobile. PUBG has historically used specific thermal HAL hints (sconfig 13 is a known BGMI/PUBG-specific HAL mode on Snapdragon devices).

Kernel tuning: identical to Game, Dynamic, Game 2.

The distinction is purely at the thermal HAL level — sconfig 13 may unlock a different FPS tier, frame-pacing policy, or power budget ceiling specific to how PUBG Mobile communicates with the HAL.

---

### 4.7 AR & VR (ID 6 → sconfig 15)

**Intent:** Latency-sensitive mixed-reality workloads. Keeps the HAL in a dedicated mode (sconfig 15) while running conservative CPU/GPU governors to maintain sustained thermal headroom — VR sessions are long, and overheating causes reprojection artifacts.

**CPU:** `schedutil`, conservative.

**GPU:** HAL-managed (no `max_clock_mhz` override).

**I/O:** `bfq`.

**sched_bore:** Off — steady task cadence preferred over burst response.

---

### 4.8 Class 0 (ID 7 → sconfig 11)

**Intent:** Low-performance class. `sconfig 11` is MIUI/HyperOS's "performance class 0" signal — typically used internally for lightweight or background-heavy apps. Conservative CPU, no GPU unlock, bfq I/O.

---

### 4.9 Camera (ID 8 → sconfig 12)

**Intent:** Camera capture workloads. The thermal HAL's sconfig 12 may unlock ISP power budgets while capping CPU/GPU to avoid thermal conflicts with the image signal processor.

Kernel: `schedutil` + `bfq` — steady, no jitter.

---

### 4.10 YouTube (ID 9 → sconfig 14)

**Intent:** Video playback. `sconfig 14` signals the HAL to optimize for decoding throughput rather than latency. Low CPU governor aggression (schedutil) is appropriate since hardware decoding does most of the work.

---

## 5. Kernel Parameters Explained

### CPU Governors

| Governor | Behavior |
|----------|----------|
| `schedutil` | Derives frequency from scheduler load signals (utilclamp, runqueue). Standard AOSP governor. Smooth, reactive, HAL-friendly. |
| `vorpal` | Custom governor. `gaming_mode 1` on cpu7 biases the prime core toward max frequency under sustained load, reducing frame time variance. |

### I/O Schedulers

| Scheduler | Behavior |
|-----------|----------|
| `bfq` | Budget Fair Queueing. Prioritizes interactive tasks, prevents background I/O starvation of foreground. Best for general use. |
| `ssg` | Aggressive throughput scheduler. Reduces queue depth management overhead. Better for burst storage access in games. |

### TCP Congestion Control

| Algorithm | Behavior |
|-----------|----------|
| `westwood` | Loss-based, sender-side. Conservative, works well on variable mobile links. |
| `bbrplus` | BBR derivative. Bandwidth-probe model, decouples loss from congestion. Higher throughput on fast/lossy links. |

### sched_bore

BORE (Burst-Oriented Response Enhancement) modifies CFS to give a short burst credit to recently-woken tasks. Effect: lower input-to-frame latency in games at the cost of slightly less predictable CPU time distribution. Off by default; enabled in all gaming profiles.

### GPU Parameters

| Node | Values | Meaning |
|------|--------|---------|
| `max_clock_mhz` | 592 (gaming) / unset (others) | Hard ceiling on Adreno 644L clock. 592 MHz is its near-peak sustained clock. |
| `max_pwrlevel` | 0 (gaming) / unset | `0` = highest perf level. Unset = HAL manages. |
| `default_pwrlevel` | 3 (Default profile only) | Mid power level when HAL is not actively managing. |

---

## 6. Fallback Behavior

The RC file ends with:

```rc
on property:persist.sys.zenith.thermal=*
    write /sys/class/thermal/thermal_message/sconfig ${persist.sys.zenith.thermal}
```

Any thermal ID not explicitly handled by a named block still gets forwarded to the HAL's `sconfig` interface. This means arbitrary HAL modes can be written directly without requiring RC changes — useful for testing undocumented HAL modes.

---

## 7. Adding a New Profile (Developer Guide)

1. Add the new sconfig value to `Profile.VALUES` and a display name to `Profile.NAMES` / `Profile.MENU_NAMES` in `Profile.kt`.
2. Add a corresponding block in `init.custom.thermal.rc`:
   ```rc
   on property:persist.sys.zenith.thermal=<new_id>
       write /sys/class/thermal/thermal_message/sconfig <new_id>
       # ... additional sysfs writes
   ```
3. Push the updated RC to the ROM's `vendor/etc/init/` or equivalent and rebuild/flash.
4. No APK change needed if only the thermal ID and name are added to the existing arrays.

---

## 8. Known Issues & Gaps

- **Default profile max_freq nodes are empty:** The RC writes `scaling_max_freq` with a blank value for the Default profile. This relies on the kernel treating an empty write as a no-op or reset, which is not guaranteed across all kernels. Should be set to explicit values (e.g., the hardware ceiling for each cluster).
- **Game, Game 2, Dynamic, Pubg are nearly identical at the kernel level.** The differentiation is entirely in the sconfig value passed to the thermal HAL. If the HAL on a given ROM does not distinguish these modes, the profiles will behave identically.
- **vorpal governor availability:** If the ROM ships without the `vorpal` driver, writing `vorpal` to `scaling_governor` silently fails and the cluster retains its current governor.
- **Missing explicit GPU min_freq in gaming profiles:** `max_clock_mhz` is set but `devfreq/min_freq` is not, leaving the floor at the HAL default.
