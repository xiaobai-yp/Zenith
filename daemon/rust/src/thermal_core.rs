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
    cluster: u8,
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
    /// True when the active profile was set via IPC (Global Profile UI).
    /// When true, foreground-app detection must not override.
    pub global_active: bool,
}

fn state() -> &'static RwLock<ThermalState> {
    STATE.get().expect("thermal_core not initialized")
}

// ── helpers ──

async fn write_sysfs(path: &str, value: &str) -> bool {
    match fs::write(path, format!("{value}\n")).await {
        Ok(_) => { crate::log!("[thermal_core] wrote '{value}' to {path}"); true }
        Err(e) => { crate::log!("[thermal_core] write {path}: {e}"); false }
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

    // Discover writable CPU scaling paths per policy (not per core —
    // writing to per-core node doesn't propagate on this kernel).
    let mut core_paths = Vec::new();
    let mut seen_policies: std::collections::HashSet<u8> = std::collections::HashSet::new();
    for i in 0..MAX_CORES {
        let cluster = cluster_for_core(i);
        if !seen_policies.insert(cluster) { continue; } // one path per policy
        let policy_dir = format!("{cpu_base}/cpufreq/policy{cluster}");
        let gov = format!("{policy_dir}/scaling_governor");
        if tokio::fs::metadata(&gov).await.is_ok() {
            core_paths.push(CorePaths {
                    cluster,
                    governor: gov,
                    scaling_max: format!("{policy_dir}/scaling_max_freq"),
                    scaling_min: format!("{policy_dir}/scaling_min_freq"),
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
        global_active: false,
    }));

    register_builtins();

    crate::log!("[thermal_core] init: {core_count} cores, {zone_count} writable zones");
    Ok(())
}

// ── profile management ──

/// Built-in profiles — hardcoded so the daemon never depends on a
/// profiles.json file being present on device. Values mirror
/// init.zenith.rc + profiles.json (thermal IDs 0,2,8-16).
pub fn builtin_profiles() -> HashMap<String, ThermalProfile> {
    macro_rules! p {
        ($id:expr, $gov0:expr, $gov4:expr, $gov7:expr,
         $max0:expr, $max4:expr, $max7:expr,
         $min0:expr, $min4:expr, $min7:expr) => {
            ThermalProfile {
                id: $id.to_string(),
                governor: Some($gov0.to_string()),
                governor4: Some($gov4.to_string()),
                governor7: Some($gov7.to_string()),
                max_freq_khz: $max0,
                min_freq_khz: $min0,
                max_freq4_khz: $max4,
                min_freq4_khz: $min4,
                max_freq7_khz: $max7,
                min_freq7_khz: $min7,
                thermal_limit_milli: vec![],
            }
        };
    }
    let mut m = HashMap::new();
    for (id, gov0, gov4, gov7, max0, max4, max7, min0, min4, min7) in [
        // Default — balanced, HAL-managed
        ("0", "schedutil", "schedutil", "schedutil", Some(1804800), Some(2419200), Some(2841600), Some(300000), Some(633000), Some(787000)),
        // Powersave (legacy)
        ("2", "powersave", "powersave", "powersave", Some(1075200), Some(1344000), Some(1862400), Some(300000), Some(710000), Some(844000)),
        // In-Calls
        ("8", "schedutil", "schedutil", "schedutil", Some(1804800), Some(2419200), Some(2841600), Some(300000), Some(633000), Some(787000)),
        // Game
        ("9", "vorpal", "vorpal", "vorpal", Some(1804800), Some(2419200), Some(3187200), Some(300000), Some(633000), Some(787000)),
        // Dynamic
        ("10", "vorpal", "vorpal", "vorpal", Some(1804800), Some(2419200), Some(2841600), Some(300000), Some(633000), Some(787000)),
        // Class 0
        ("11", "schedutil", "schedutil", "schedutil", Some(1804800), Some(2419200), Some(2841600), Some(300000), Some(633000), Some(787000)),
        // Camera
        ("12", "schedutil", "schedutil", "schedutil", Some(1804800), Some(2419200), Some(2841600), Some(300000), Some(633000), Some(787000)),
        // Pubg
        ("13", "vorpal", "vorpal", "vorpal", Some(1804800), Some(2419200), Some(3187200), Some(300000), Some(633000), Some(787000)),
        // YouTube
        ("14", "schedutil", "schedutil", "schedutil", Some(1804800), Some(2419200), Some(2841600), Some(300000), Some(633000), Some(787000)),
        // AR & VR
        ("15", "schedutil", "schedutil", "schedutil", Some(1804800), Some(2419200), Some(2841600), Some(300000), Some(633000), Some(787000)),
        // Game 2
        ("16", "vorpal", "vorpal", "vorpal", Some(1804800), Some(2246400), Some(2745600), Some(300000), Some(633000), Some(787000)),
    ] {
        m.insert(id.to_string(), p!(id, gov0, gov4, gov7, max0, max4, max7, min0, min4, min7));
    }
    m
}

/// Register all built-in profiles.
pub fn register_builtins() {
    let profiles = builtin_profiles();
    let count = profiles.len();
    let mut s = state().write().unwrap();
    for (id, p) in profiles {
        s.profiles.insert(id, p);
    }
    let _ = writeln!(std::io::stderr(), "[thermal_core] register_builtins: {count} profiles loaded");
}

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

/// Mark global profile as active (set from IPC / Global Profile UI).
pub fn set_global_active(active: bool) {
    state().write().unwrap().global_active = active;
}

pub fn is_global_active() -> bool {
    state().read().unwrap().global_active
}

// ── apply profile ──

pub async fn apply_profile(profile_id: &str) -> Result<(), String> {
    // Daemon hanya menulis sconfig — semua konfigurasi hardware
    // (governor, freq, GPU, I/O, TCP, scheduler) ditangani init.rc
    // via property trigger. Daemon tidak menulis sysfs lain.
    let sconfig_path = zen_path!("/sys/class/thermal/thermal_message/sconfig");
    if !write_sysfs(&sconfig_path, profile_id).await {
        return Err(format!("write sconfig: {profile_id}"));
    }

    // Mark active
    {
        let mut s = state().write().unwrap();
        s.active_profile = Some(profile_id.to_string());
    }

    crate::log!("[thermal_core] applied profile: {profile_id} (sconfig only)");
    Ok(())
}

/// Apply a global profile and pin it (prevents foreground detection from overriding).
pub async fn apply_global_profile(profile_id: &str) -> Result<(), String> {
    set_global_active(true);
    apply_profile(profile_id).await
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
    crate::log!("[thermal_core] governor → '{governor}' core={core_id:?}");
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
    crate::log!("[thermal_core] max_freq → {freq_khz} kHz core={core_id:?}");
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

    crate::log!("[thermal_core] zone {zone_id} limit → {temp_milli} mC");
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