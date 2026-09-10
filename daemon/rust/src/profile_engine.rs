// profile_engine.rs — Load profiles.json, map packages → profile IDs,
use std::io::Write;
// register each profile with thermal_core.

use crate::thermal_core;
use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};
use tokio::fs;

#[derive(Debug, Clone, serde::Deserialize)]
pub struct RawProfile {
    pub cpu0_max: Option<String>,
    pub cpu0_min: Option<String>,
    pub cpu4_max: Option<String>,
    pub cpu4_min: Option<String>,
    pub cpu7_max: Option<String>,
    pub cpu7_min: Option<String>,
    pub cpu0_gov: Option<String>,
    pub cpu4_gov: Option<String>,
    pub cpu7_gov: Option<String>,
}

struct EngineState {
    profiles: HashMap<i32, RawProfile>,
    pkg_map: HashMap<String, i32>,
    path: String,
}

static STATE: OnceLock<RwLock<EngineState>> = OnceLock::new();

fn state() -> &'static RwLock<EngineState> {
    STATE.get().expect("profile_engine not initialized")
}

fn parse_khz(s: &str) -> Option<u32> {
    s.trim().parse::<u32>().ok()
}

fn to_thermal_profile(id: i32, raw: &RawProfile) -> thermal_core::ThermalProfile {
    thermal_core::ThermalProfile {
        id: id.to_string(),
        governor: raw.cpu0_gov.clone(),
        governor4: raw.cpu4_gov.clone(),
        governor7: raw.cpu7_gov.clone(),
        max_freq_khz: raw.cpu0_max.as_deref().and_then(parse_khz),
        min_freq_khz: raw.cpu0_min.as_deref().and_then(parse_khz),
        max_freq4_khz: raw.cpu4_max.as_deref().and_then(parse_khz),
        min_freq4_khz: raw.cpu4_min.as_deref().and_then(parse_khz),
        max_freq7_khz: raw.cpu7_max.as_deref().and_then(parse_khz),
        min_freq7_khz: raw.cpu7_min.as_deref().and_then(parse_khz),
        thermal_limit_milli: vec![],
    }
}

pub async fn init(path: &str) -> Result<(), String> {
    let json = fs::read_to_string(path)
        .await
        .map_err(|e| format!("read profiles: {e}"))?;
    let raw: HashMap<String, RawProfile> =
        serde_json::from_str(&json).map_err(|e| format!("parse profiles: {e}"))?;

    let mut profiles = HashMap::new();
    for (k, v) in &raw {
        if let Ok(id) = k.parse::<i32>() {
            profiles.insert(id, v.clone());
            thermal_core::register_profile(to_thermal_profile(id, v));
        }
    }

    crate::log!(
        "[profile_engine] loaded {} profiles from {path}",
        profiles.len()
    );

    let _ = STATE.set(RwLock::new(EngineState {
        profiles,
        pkg_map: HashMap::new(),
        path: path.to_string(),
    }));
    Ok(())
}

pub fn lookup(package: &str) -> i32 {
    let s = state().read().unwrap();
    *s.pkg_map.get(package).unwrap_or(&0)
}

pub fn map_app(package: &str, profile_id: i32) {
    state().write().unwrap().pkg_map.insert(package.to_string(), profile_id);
    crate::log!("[profile_engine] mapped {package} → profile {profile_id}");
}

/** Clear all per-app mappings (reset to daemon default = profile 0 lookup). */
pub fn reset_map() {
    state().write().unwrap().pkg_map.clear();
    crate::log!("[profile_engine] package map cleared");
}

pub async fn reload() -> Result<(), String> {
    let path = state().read().unwrap().path.clone();
    init(&path).await
}

pub fn export_map() -> HashMap<String, i32> {
    state().read().unwrap().pkg_map.clone()
}

pub fn list_profiles() -> Vec<i32> {
    let s = state().read().unwrap();
    s.profiles.keys().copied().collect::<Vec<_>>()
}
