// benchmark.rs — 300-point ring buffer for benchmark recording.
use std::io::Write;

use crate::sysfs_monitor::SysfsSnapshot;
use serde::Serialize;
use std::collections::VecDeque;
use std::sync::{OnceLock, RwLock};

const MAX_POINTS: usize = 300;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BenchPoint {
    pub timestamp_ms: u64,
    pub fps: i32,
    pub fps_short: i32,
    pub fps_avg: i32,
    pub temp_milli: i64,
    pub batt_pct: i32,
    pub current_ma: i64,
}

struct BenchState {
    points: VecDeque<BenchPoint>,
    recording: bool,
    start_time_ms: u64,
    frame_count: u64,
}

static STATE: OnceLock<RwLock<BenchState>> = OnceLock::new();

fn state() -> &'static RwLock<BenchState> {
    STATE.get().expect("benchmark not initialized")
}

pub fn init() {
    let _ = STATE.set(RwLock::new(BenchState {
        points: VecDeque::with_capacity(MAX_POINTS),
        recording: false,
        start_time_ms: 0,
        frame_count: 0,
    }));
}

pub fn start() {
    let mut s = state().write().unwrap();
    s.points.clear();
    s.recording = true;
    s.start_time_ms = now_ms();
    s.frame_count = 0;
    crate::log!("[benchmark] recording started");
}

pub fn stop() {
    state().write().unwrap().recording = false;
    crate::log!(
        "[benchmark] recording stopped, {} points",
        state().read().unwrap().points.len()
    );
}

pub fn is_active() -> bool {
    state().read().unwrap().recording
}

pub fn elapsed_ms() -> u64 {
    let s = state().read().unwrap();
    if s.start_time_ms == 0 { 0 } else { now_ms().saturating_sub(s.start_time_ms) }
}

pub fn frame_count() -> u64 {
    state().read().unwrap().frame_count
}

pub fn record(snapshot: &SysfsSnapshot, fps: i32, fps_short: i32, fps_avg: i32) {
    let mut s = state().write().unwrap();
    if !s.recording {
        return;
    }
    s.frame_count += 1;

    let temp = snapshot
        .thermal_zones
        .first()
        .map(|z| z.temp_milli)
        .unwrap_or(0);

    let point = BenchPoint {
        timestamp_ms: now_ms(),
        fps,
        fps_short,
        fps_avg,
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
