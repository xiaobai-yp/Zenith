// sysfs_monitor.rs — Read all sysfs state: thermal zones, battery, CPU.
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
    pub cur_freq: i64,
    pub max_freq: i64,
    pub busy_pct: i32,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct SysfsSnapshot {
    pub thermal_zones: Vec<ThermalZone>,
    pub battery: BatteryInfo,
    pub cpu_policy0: CpuInfo,
    pub cpu_policy4: Option<CpuInfo>,
    pub cpu_policy7: Option<CpuInfo>,
    pub gpu: Option<GpuInfo>,
    pub screen_on: bool,
}

struct MonitorState {
    zone_paths: Vec<(u32, String, String, String)>, // (id, type_path, temp_path, trip_path)
}

static STATE: OnceLock<RwLock<MonitorState>> = OnceLock::new();

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

    // GPU (Qualcomm kgsl)
    let gpu_base = zen_path!("/sys/class/kgsl/kgsl-3d0");
    let gpu_cur = read_i64(&format!("{gpu_base}/gpuclk")).await
        .or_else(|| read_i64(&format!("{gpu_base}/devfreq/cur_freq")).await);
    let gpu = gpu_cur.map(|cur| {
        let max = read_i64(&format!("{gpu_base}/max_gpuclk")).await.unwrap_or(0);
        let busy_str = std::fs::read_to_string(format!("{gpu_base}/gpu_busy_percentage"))
            .ok()
            .and_then(|s| s.trim().trim_end_matches('%').parse::<i32>().ok())
            .unwrap_or(0);
        GpuInfo { cur_freq: cur, max_freq: max, busy_pct: busy_str }
    });

    SysfsSnapshot {
        thermal_zones,
        battery,
        cpu_policy0,
        cpu_policy4,
        cpu_policy7,
        gpu,
        screen_on,
    }
}
