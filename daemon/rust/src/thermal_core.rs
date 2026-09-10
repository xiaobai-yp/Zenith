// thermal_core.rs — Port of thermal_core.c
use std::io::Write;
// Applies thermal profiles: governor, freq limits, thermal zone limits.
// Per-cluster: cores 0-3=little, 4-6=big, 7=prime.

use crate::zen_path;
use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};
use tokio::fs;

const MAX_CORES: usize = 8;
const MAX_THERMAL_ZONES: usize = 16;

#[derive(Debug, Clone, Default)]
pub struct ThermalProfile {
    pub id: String,
    pub governor: Option<String>,       // policy0 (little)
    pub governor4: Option<String>,      // policy4 (big)
    pub governor7: Option<String>,      // policy7 (prime)
    pub max_freq_khz: Option<u32>,      // policy0
    pub min_freq_khz: Option<u32>,
    pub max_freq4_khz: Option<u32>,
    pub min_freq4_khz: Option<u32>,
    pub max_freq7_khz: Option<u32>,
    pub min_freq7_khz: Option<u32>,
    pub thermal_limit_milli: Vec<u32>,  // per-zone
}

#[derive(Debug, Default, Clone)]
struct CorePaths {
    governor: String,
    scaling_max: String,
    scaling_min: String,
}

#[derive(Clone, Debug)]
pub struct ThermalStatus {
    pub active_profile: Option<String>,
    pub core_count: usize,
    pub zone_count: usize,
}

static STATE: OnceLock<RwLock<ThermalState>> = OnceLock::new();

struct ThermalState {
    profiles: HashMap<String, ThermalProfile>,
    active_profile: Option<String>,
    core_paths: Vec<CorePaths>,
    zone_mode_paths: Vec<String>,
}

fn state() -> &'static RwLock<ThermalState> {
    STATE.get().expect("thermal_core not initialized")
}

// ── helpers ──

async fn write_sysfs(path: &str, value: &str) -> bool {
    match fs::write(path, format!("{value}\n")).await {
        Ok(_) => { log!("[thermal_core] wrote '{value}' to {path}"); true }
        Err(e) => { log!("[thermal_core] write {path}: {e}"); false }
    }
}

async fn write_sysfs_u32(path: &str, value: u32) -> bool {
    write_sysfs(path, &value.to_string()).await
}

// ── cluster mapping: core idx → cluster (0=little, 4=big, 7=prime) ──

fn cluster_for_core(i: usize) -> u8 {
    if i <= 3 { 0 } else if i <= 6 { 4 } else { 7 }
}

// ── init ──

pub async fn init() -> Result<(), String> {
    let cpu_base = zen_path!("/sys/devices/system/cpu");
    let thermal_base = zen_path!("/sys/class/thermal");

    // Discover writable CPU scaling paths per core
    let mut core_paths = Vec::new();
    for i in 0..MAX_CORES {
        let gov = format!("{cpu_base}/cpu{i}/cpufreq/scaling_governor");
        // Check existence + write permission (access W_OK) — don't
        // write garbage to sysfs as a writability test.
        if tokio::fs::metadata(&gov).await.is_ok() {
            core_paths.push(CorePaths {
                governor: gov,
                scaling_max: format!("{cpu_base}/cpu{i}/cpufreq/scaling_max_freq"),
                scaling_min: format!("{cpu_base}/cpu{i}/cpufreq/scaling_min_freq"),
            });
        }
    }

    // Discover writable thermal zone mode paths
    let mut zone_mode_paths = Vec::new();
    if let Ok(mut entries) = fs::read_dir(thermal_base).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name().to_string_lossy().to_string();
            if !name.starts_with("thermal_zone") { continue; }
            let mode_path = format!("{}/{name}/mode", thermal_base);
            if tokio::fs::metadata(&mode_path).await.is_ok() {
                zone_mode_paths.push(mode_path);
                if zone_mode_paths.len() >= MAX_THERMAL_ZONES { break; }
            }
        }
    }

    let core_count = core_paths.len();
    let zone_count = zone_mode_paths.len();

    let _ = STATE.set(RwLock::new(ThermalState {
        profiles: HashMap::new(),
        active_profile: None,
        core_paths,
        zone_mode_paths,
    }));

    log!("[thermal_core] init: {core_count} cores, {zone_count} writable zones");
    Ok(())
}

// ── profile management ──

pub fn register_profile(profile: ThermalProfile) {
    let id = profile.id.clone();
    let mut s = state().write().unwrap();
    s.profiles.insert(id, profile);
}

pub fn get_active_profile() -> Option<String> {
    state().read().unwrap().active_profile.clone()
}

pub fn list_profiles() -> Vec<String> {
    state().read().unwrap().profiles.keys().cloned().collect()
}

// ── apply profile ──

pub async fn apply_profile(profile_id: &str) -> Result<(), String> {
    let profile = {
        let s = state().read().unwrap();
        s.profiles.get(profile_id)
            .cloned()
            .ok_or_else(|| format!("profile not found: {profile_id}"))?
    };

    let (core_paths, zone_mode_paths) = {
        let s = state().read().unwrap();
        (s.core_paths.clone(), s.zone_mode_paths.clone())
    };

    for i in 0..core_paths.len() {
        let cluster = cluster_for_core(i);

        // Pick governor for this cluster
        let gov = match cluster {
            0 => profile.governor.as_deref(),
            4 => profile.governor4.as_deref()
                .or(profile.governor.as_deref()),
            _ => profile.governor7.as_deref()
                .or(profile.governor.as_deref()),
        };

        // Pick freq limits for this cluster
        let max_f = match cluster {
            0 => profile.max_freq_khz,
            4 => profile.max_freq4_khz.or(profile.max_freq_khz),
            _ => profile.max_freq7_khz.or(profile.max_freq_khz),
        };

        let min_f = match cluster {
            0 => profile.min_freq_khz,
            4 => profile.min_freq4_khz.or(profile.min_freq_khz),
            _ => profile.min_freq7_khz.or(profile.min_freq_khz),
        };

        if let Some(core) = core_paths.get(i) {
            if let Some(g) = gov {
                write_sysfs(&core.governor, g).await;
            }
            if let Some(f) = max_f {
                write_sysfs_u32(&core.scaling_max, f).await;
            }
            if let Some(f) = min_f {
                write_sysfs_u32(&core.scaling_min, f).await;
            }
        }
    }

    // Apply thermal zone limits
    for (i, limit) in profile.thermal_limit_milli.iter().enumerate() {
        if *limit > 0 {
            if let Some(path) = zone_mode_paths.get(i) {
                write_sysfs(path, &limit.to_string()).await;
            }
        }
    }

    // Mark active
    {
        let mut s = state().write().unwrap();
        s.active_profile = Some(profile_id.to_string());
    }

    log!("[thermal_core] applied profile: {profile_id}");
    Ok(())
}

// ── direct setters ──

pub async fn set_governor(core_id: Option<i32>, governor: &str) -> Result<(), String> {
    let core_paths = state().read().unwrap().core_paths.clone();
    match core_id {
        None => {
            for core in &core_paths {
                write_sysfs(&core.governor, governor).await;
            }
        }
        Some(id) if id >= 0 && (id as usize) < core_paths.len() => {
            write_sysfs(&core_paths[id as usize].governor, governor).await;
        }
        _ => return Err(format!("invalid core_id: {core_id:?}")),
    }
    log!("[thermal_core] governor → '{governor}' core={core_id:?}");
    Ok(())
}

pub async fn set_max_freq(core_id: Option<i32>, freq_khz: u32) -> Result<(), String> {
    let core_paths = state().read().unwrap().core_paths.clone();
    match core_id {
        None => {
            for core in &core_paths {
                write_sysfs_u32(&core.scaling_max, freq_khz).await;
            }
        }
        Some(id) if id >= 0 && (id as usize) < core_paths.len() => {
            write_sysfs_u32(&core_paths[id as usize].scaling_max, freq_khz).await;
        }
        _ => return Err(format!("invalid core_id: {core_id:?}")),
    }
    log!("[thermal_core] max_freq → {freq_khz} kHz core={core_id:?}");
    Ok(())
}

pub async fn set_thermal_limit(zone_id: usize, temp_milli: u32) -> Result<(), String> {
    let zone_mode_paths = state().read().unwrap().zone_mode_paths.clone();
    let path = zone_mode_paths.get(zone_id)
        .ok_or_else(|| format!("invalid zone_id: {zone_id}"))?;

    if temp_milli == 0 {
        write_sysfs(path, "1").await;
    } else {
        write_sysfs_u32(path, temp_milli).await;
    }

    log!("[thermal_core] zone {zone_id} limit → {temp_milli} mC");
    Ok(())
}

pub fn status() -> ThermalStatus {
    let s = state().read().unwrap();
    ThermalStatus {
        active_profile: s.active_profile.clone(),
        core_count: s.core_paths.len(),
        zone_count: s.zone_mode_paths.len(),
    }
}