// sysfs_monitor.rs — Read all sysfs state: thermal zones, battery, CPU, GPU.
use std::io::Write;

use crate::zen_path;
use serde::Serialize;
use std::sync::{OnceLock, RwLock};
use tokio::fs;

#[derive(Debug, Clone, Serialize, Default)]
pub struct ThermalZone {
    pub id: u32,
    pub name: String,
    pub temp_milli: i64,
    pub trip_point_0: i64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct BatteryInfo {
    pub capacity: i32,
    pub current_ua: i64,
    pub voltage_uv: i64,
    pub temp_centi: i64,
    pub online: bool,
    pub power_mw: i64,  // voltage_uv * current_ua / 1e9 → milliwatt
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct CpuInfo {
    pub governor: String,
    pub cur_freq: u32,
    pub max_freq: u32,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct GpuInfo {
    pub vendor: String,
    pub cur_freq: i64,
    pub max_freq: i64,
    pub busy_pct: i32,
    pub gpu_temp_c: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct DdrInfo {
    pub cur_freq: u32,    // kHz
    pub max_freq: u32,    // kHz
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct SysfsSnapshot {
    pub thermal_zones: Vec<ThermalZone>,
    pub battery: BatteryInfo,
    pub cpu_policy0: CpuInfo,
    pub cpu_policy4: Option<CpuInfo>,
    pub cpu_policy7: Option<CpuInfo>,
    pub cpu_load_pct: f64,
    pub gpu: Option<GpuInfo>,
    pub ddr: Option<DdrInfo>,
    pub screen_on: bool,
}

struct MonitorState {
    zone_paths: Vec<(u32, String, String, String)>, // (id, type_path, temp_path, trip_path)
}

static STATE: OnceLock<RwLock<MonitorState>> = OnceLock::new();

// --- CPU load delta tracking ---
#[derive(Debug, Clone, Copy, Default)]
struct CpuStat {
    total: u64,
    idle: u64,
}

static PREV_CPU_STAT: OnceLock<RwLock<CpuStat>> = OnceLock::new();

fn prev_stat() -> &'static RwLock<CpuStat> {
    PREV_CPU_STAT.get_or_init(|| RwLock::new(CpuStat::default()))
}

fn state() -> &'static RwLock<MonitorState> {
    STATE.get().expect("sysfs_monitor not initialized")
}

async fn read_i64(path: &str) -> Option<i64> {
    fs::read_to_string(path)
        .await
        .ok()
        .and_then(|s| s.trim().parse().ok())
}

async fn read_u32(path: &str) -> Option<u32> {
    fs::read_to_string(path)
        .await
        .ok()
        .and_then(|s| s.trim().parse().ok())
}

async fn read_str(path: &str) -> String {
    fs::read_to_string(path)
        .await
        .map(|s| s.trim().to_string())
        .unwrap_or_default()
}

async fn read_cpu_policy(prefix: &str) -> CpuInfo {
    CpuInfo {
        governor: read_str(&format!("{prefix}/scaling_governor")).await,
        cur_freq: read_u32(&format!("{prefix}/scaling_cur_freq")).await.unwrap_or(0),
        max_freq: read_u32(&format!("{prefix}/scaling_max_freq")).await.unwrap_or(0),
    }
}

/// Read /proc/stat first line, return (total_ticks, idle_ticks).
async fn read_proc_stat() -> Option<CpuStat> {
    let content = fs::read_to_string("/proc/stat").await.ok()?;
    let first = content.lines().next()?;
    // Format: cpu <user> <nice> <system> <idle> <iowait> <irq> <softirq> <steal> ...
    let parts: Vec<&str> = first.split_whitespace().collect();
    if parts.len() < 5 { return None; }
    let total: u64 = parts[1..].iter().filter_map(|s| s.parse::<u64>().ok()).sum();
    let idle: u64 = parts[4].parse().ok()?;
    Some(CpuStat { total, idle })
}

/// Compute CPU load% from delta of /proc/stat.
async fn cpu_load_pct() -> f64 {
    let curr = match read_proc_stat().await {
        Some(s) => s,
        None => return 0.0,
    };
    let mut prev = prev_stat().write().unwrap();
    let total_delta = curr.total.saturating_sub(prev.total);
    let idle_delta = curr.idle.saturating_sub(prev.idle);
    *prev = curr;
    if total_delta == 0 { return 0.0; }
    ((total_delta - idle_delta) as f64 / total_delta as f64) * 100.0
}

/// Find GPU temp from thermal zones (gpu/kgsl/mali/adreno).
fn gpu_temp_from_zones(zones: &[ThermalZone]) -> Option<f64> {
    for z in zones {
        let name = z.name.to_lowercase();
        if name.contains("gpu") || name.contains("kgsl")
            || name.contains("mali") || name.contains("adreno")
        {
            return Some(z.temp_milli as f64 / 1000.0);
        }
    }
    None
}

/// Read devfreq load file (returns busy% or 0).
async fn read_devfreq_load(path: &str) -> i32 {
    // Some devfreq nodes have a "load" file with avg freq info;
    // others have gpu_busy_percentage-style. Try common patterns.
    if let Some(val) = read_i64(&format!("{path}/load")).await {
        return val as i32;
    }
    // Some expose "trans_stat" — just report 0 if no direct load
    0
}

/// Multi-vendor GPU detection: kgsl → devfreq (*gpu*) → mali → MTK.
async fn detect_gpu() -> Option<GpuInfo> {
    // 1. Qualcomm kgsl
    let kgsl = zen_path!("/sys/class/kgsl/kgsl-3d0");
    if let Some(cur) = read_i64(&format!("{kgsl}/gpuclk")).await {
        let max = read_i64(&format!("{kgsl}/max_gpuclk")).await.unwrap_or(0);
        let busy = fs::read_to_string(format!("{kgsl}/gpu_busy_percentage"))
            .await
            .ok()
            .and_then(|s| {
                let s = s.trim().trim_end_matches('%').trim();
                s.parse::<i32>().ok()
            })
            .unwrap_or(0);
        return Some(GpuInfo {
            vendor: "kgsl".into(),
            cur_freq: cur,
            max_freq: max,
            busy_pct: busy,
            gpu_temp_c: None,
        });
    }
    // kgsl fallback: devfreq under kgsl
    if let Some(cur) = read_i64(&format!("{kgsl}/devfreq/cur_freq")).await {
        let max = read_i64(&format!("{kgsl}/devfreq/max_freq")).await.unwrap_or(0);
        return Some(GpuInfo {
            vendor: "kgsl".into(),
            cur_freq: cur,
            max_freq: max,
            busy_pct: 0,
            gpu_temp_c: None,
        });
    }

    // 2. devfreq platform entries matching *gpu*
    let platform_dir = zen_path!("/sys/devices/platform");
    if let Ok(mut entries) = fs::read_dir(platform_dir).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name().to_string_lossy().to_lowercase();
            if !name.contains("gpu") { continue; }
            let df = entry.path().join("devfreq");
            if let Ok(mut df_entries) = fs::read_dir(&df).await {
                while let Ok(Some(df_entry)) = df_entries.next_entry().await {
                    let dfb = df_entry.path();
                    let dfbs = dfb.to_string_lossy();
                    if let Some(cur) = read_i64(&format!("{dfbs}/cur_freq")).await {
                        let max = read_i64(&format!("{dfbs}/max_freq")).await.unwrap_or(0);
                        let busy = read_devfreq_load(&dfbs).await;
                        return Some(GpuInfo {
                            vendor: "devfreq-gpu".into(),
                            cur_freq: cur,
                            max_freq: max,
                            busy_pct: busy,
                            gpu_temp_c: None,
                        });
                    }
                }
            }
        }
    }

    // 3. Mali via /sys/class/devfreq
    let df_class = zen_path!("/sys/class/devfreq");
    if let Ok(mut entries) = fs::read_dir(df_class).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name().to_string_lossy().to_lowercase();
            if name.contains("mali") {
                let base = entry.path().to_string_lossy().to_string();
                if let Some(cur) = read_i64(&format!("{base}/cur_freq")).await {
                    let max = read_i64(&format!("{base}/max_freq")).await.unwrap_or(0);
                    let busy = read_devfreq_load(&base).await;
                    return Some(GpuInfo {
                        vendor: "mali".into(),
                        cur_freq: cur,
                        max_freq: max,
                        busy_pct: busy,
                        gpu_temp_c: None,
                    });
                }
            }
        }
    }

    // 4. MediaTek via /sys/class/devfreq (mtk-*)
    if let Ok(mut entries) = fs::read_dir(df_class).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name().to_string_lossy().to_lowercase();
            if name.starts_with("mtk") || (name.contains("disp") && name.contains("gpu")) {
                let base = entry.path().to_string_lossy().to_string();
                if let Some(cur) = read_i64(&format!("{base}/cur_freq")).await {
                    let max = read_i64(&format!("{base}/max_freq")).await.unwrap_or(0);
                    return Some(GpuInfo {
                        vendor: "mtk".into(),
                        cur_freq: cur,
                        max_freq: max,
                        busy_pct: 0,
                        gpu_temp_c: None,
                    });
                }
            }
        }
    }

    None
}

pub async fn init() {
    let thermal_base = zen_path!("/sys/class/thermal");
    let mut zone_paths = Vec::new();
    if let Ok(mut entries) = fs::read_dir(thermal_base).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name().to_string_lossy().to_string();
            if !name.starts_with("thermal_zone") {
                continue;
            }
            if let Ok(id) = name.trim_start_matches("thermal_zone").parse::<u32>() {
                let base = format!("{thermal_base}/{name}");
                zone_paths.push((
                    id,
                    format!("{base}/type"),
                    format!("{base}/temp"),
                    format!("{base}/trip_point_0_temp"),
                ));
            }
        }
    }
    zone_paths.sort_by_key(|z| z.0);

    crate::log!("[sysfs_monitor] init: {} thermal zones", zone_paths.len());
    let _ = STATE.set(RwLock::new(MonitorState { zone_paths }));
    // Seed prev_cpu_stat so first delta isn't 100%
    let _ = PREV_CPU_STAT.set(RwLock::new(CpuStat::default()));
    if let Some(seed) = read_proc_stat().await {
        *prev_stat().write().unwrap() = seed;
    }
}

pub async fn read() -> SysfsSnapshot {
    // Thermal zones
    let zone_paths = state().read().unwrap().zone_paths.clone();
    let mut thermal_zones = Vec::new();
    for (id, type_p, temp_p, trip_p) in &zone_paths {
        thermal_zones.push(ThermalZone {
            id: *id,
            name: read_str(type_p).await,
            temp_milli: read_i64(temp_p).await.unwrap_or(0),
            trip_point_0: read_i64(trip_p).await.unwrap_or(0),
        });
    }

    // Battery
    let batt_base = zen_path!("/sys/class/power_supply/battery");
    let voltage_uv = read_i64(&format!("{batt_base}/voltage_now")).await.unwrap_or(0);
    let current_ua = read_i64(&format!("{batt_base}/current_now")).await.unwrap_or(0);
    // Power: voltage_uv * current_ua / 1e9 = milliwatt
    let power_mw = if voltage_uv > 0 && current_ua != 0 {
        voltage_uv * current_ua.abs() / 1_000_000_000
    } else {
        0
    };
    let battery = BatteryInfo {
        capacity: read_u32(&format!("{batt_base}/capacity"))
            .await
            .unwrap_or(0) as i32,
        current_ua,
        voltage_uv,
        temp_centi: read_i64(&format!("{batt_base}/temp"))
            .await
            .unwrap_or(0),
        online: {
            let status = read_str(&format!("{batt_base}/status"))
                .await;
            status == "Charging" || status == "Full"
        },
        power_mw,
    };

    // CPU
    let cpu_base = zen_path!("/sys/devices/system/cpu/cpufreq");
    let cpu_policy0 = read_cpu_policy(&format!("{cpu_base}/policy0")).await;
    let cpu_policy4 = read_cpu_policy(&format!("{cpu_base}/policy4")).await;
    let cpu_policy7 = read_cpu_policy(&format!("{cpu_base}/policy7")).await;

    let cpu_policy4 = if cpu_policy4.cur_freq > 0 || !cpu_policy4.governor.is_empty() {
        Some(cpu_policy4)
    } else {
        None
    };
    let cpu_policy7 = if cpu_policy7.cur_freq > 0 || !cpu_policy7.governor.is_empty() {
        Some(cpu_policy7)
    } else {
        None
    };

    // CPU load% from /proc/stat delta
    let load = cpu_load_pct().await;

    // Screen state: brightness > 0 means screen on
    let screen_brightness = read_i64("/sys/class/leds/lcd-backlight/brightness")
        .await
        .unwrap_or(-1);
    let screen_on = if screen_brightness >= 0 {
        screen_brightness > 0
    } else {
        // fallback: check panel backlight
        read_i64("/sys/class/backlight/panel0-backlight/brightness")
            .await
            .map_or(true, |v| v > 0)
    };

    // GPU — multi-vendor fallback detection
    let mut gpu = detect_gpu().await;
    // Attach GPU temp from thermal zones
    if let Some(ref mut g) = gpu {
        g.gpu_temp_c = gpu_temp_from_zones(&thermal_zones);
    }

    // DDR — Qualcomm bus_dcvs
    let ddr_base = zen_path!("/sys/devices/system/cpu/bus_dcvs/DDR");
    let ddr = {
        let cur = read_u32(&format!("{ddr_base}/cur_freq")).await  // Scene-style: root DDR path
            .or_else(|| {
                // fallback: try silver memlat
                let path = format!("{ddr_base}/soc:qcom,memlat:ddr:silver/cur_freq");
                std::fs::read_to_string(&path).ok().and_then(|s| s.trim().parse().ok())
            });
        let max_silver = read_u32(&format!("{ddr_base}/soc:qcom,memlat:ddr:silver/max_freq")).await.unwrap_or(0);
        let max_prime = read_u32(&format!("{ddr_base}/soc:qcom,memlat:ddr:prime/max_freq")).await.unwrap_or(0);
        cur.map(|c| DdrInfo { cur_freq: c, max_freq: max_silver.max(max_prime) })
    };

    SysfsSnapshot {
        thermal_zones,
        battery,
        cpu_policy0,
        cpu_policy4,
        cpu_policy7,
        cpu_load_pct: load,
        gpu,
        ddr,
        screen_on,
    }
}
