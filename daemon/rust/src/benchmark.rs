// benchmark.rs — 300-point ring buffer for benchmark recording.

use crate::sysfs_monitor::SysfsSnapshot;
use serde::Serialize;
use std::collections::VecDeque;
use std::sync::{OnceLock, RwLock};

const MAX_POINTS: usize = 300;

#[derive(Debug, Clone, Serialize)]
pub struct BenchPoint {
    pub timestamp_ms: u64,
    pub fps: i32,
    pub temp_milli: i64,
    pub batt_pct: i32,
    pub current_ma: i64,
}

struct BenchState {
    points: VecDeque<BenchPoint>,
    recording: bool,
}

static STATE: OnceLock<RwLock<BenchState>> = OnceLock::new();

fn state() -> &'static RwLock<BenchState> {
    STATE.get().expect("benchmark not initialized")
}

pub fn init() {
    let _ = STATE.set(RwLock::new(BenchState {
        points: VecDeque::with_capacity(MAX_POINTS),
        recording: false,
    }));
}

pub fn start() {
    let mut s = state().write().unwrap();
    s.points.clear();
    s.recording = true;
    log!("[benchmark] recording started");
}

pub fn stop() {
    state().write().unwrap().recording = false;
    log!(
        "[benchmark] recording stopped, {} points",
        state().read().unwrap().points.len()
    );
}

pub fn is_active() -> bool {
    state().read().unwrap().recording
}

pub fn record(snapshot: &SysfsSnapshot, fps: i32) {
    let mut s = state().write().unwrap();
    if !s.recording {
        return;
    }

    let temp = snapshot
        .thermal_zones
        .first()
        .map(|z| z.temp_milli)
        .unwrap_or(0);

    let point = BenchPoint {
        timestamp_ms: now_ms(),
        fps,
        temp_milli: temp,
        batt_pct: snapshot.battery.capacity,
        current_ma: snapshot.battery.current_ua / 1000,
    };

    if s.points.len() >= MAX_POINTS {
        s.points.pop_front();
    }
    s.points.push_back(point);
}

pub fn export_json() -> String {
    let s = state().read().unwrap();
    serde_json::to_string(&s.points.iter().collect::<Vec<_>>())
        .unwrap_or_else(|_| "[]".to_string())
}

pub fn get_last_points(n: usize) -> Vec<BenchPoint> {
    let s = state().read().unwrap();
    let len = s.points.len();
    let skip = len.saturating_sub(n);
    s.points.iter().skip(skip).cloned().collect()
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
