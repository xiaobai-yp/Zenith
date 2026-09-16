// benchmark.rs — 300-point ring buffer for benchmark recording.
use std::io::Write;

use crate::sysfs_monitor::SysfsSnapshot;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::{OnceLock, RwLock};

const MAX_POINTS: usize = 300;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BenchPoint {
    pub timestamp_ms: u64,
    // FPS
    pub fps: i32,
    pub fps_short: i32,
    pub fps_avg: i32,
    // CPU
    pub cpu_load: f64,        // total CPU load %
    pub cpu0_freq: u32,       // policy0 current freq kHz
    pub cpu0_max: u32,        // policy0 max freq kHz
    pub cpu4_freq: u32,       // policy4 current freq kHz
    pub cpu4_max: u32,        // policy4 max freq kHz
    pub cpu7_freq: u32,       // policy7 current freq kHz
    pub cpu7_max: u32,        // policy7 max freq kHz
    // GPU
    pub gpu_freq: i64,        // GPU current freq Hz
    pub gpu_max: i64,         // GPU max freq Hz
    pub gpu_busy: i32,        // GPU busy %
    pub gpu_temp: f64,        // GPU temp °C (0 if unavailable)
    // Battery / Power
    pub batt_pct: i32,        // battery %
    pub batt_temp: f64,       // battery temp °C
    pub current_ma: i64,      // current mA
    pub power_mw: i64,        // power mW
    // DDR
    pub ddr_freq: u32,        // DDR current freq kHz
    pub ddr_max: u32,         // DDR max freq kHz
    // Thermal
    pub temp_milli: i64,      // primary thermal zone temp (milli°C)
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
    if s.start_time_ms == 0 {
        0
    } else {
        now_ms().saturating_sub(s.start_time_ms)
    }
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
        // CPU
        cpu_load: snapshot.cpu_load_pct,
        cpu0_freq: snapshot.cpu_policy0.cur_freq,
        cpu0_max: snapshot.cpu_policy0.max_freq,
        cpu4_freq: snapshot.cpu_policy4.as_ref().map(|c| c.cur_freq).unwrap_or(0),
        cpu4_max: snapshot.cpu_policy4.as_ref().map(|c| c.max_freq).unwrap_or(0),
        cpu7_freq: snapshot.cpu_policy7.as_ref().map(|c| c.cur_freq).unwrap_or(0),
        cpu7_max: snapshot.cpu_policy7.as_ref().map(|c| c.max_freq).unwrap_or(0),
        // GPU
        gpu_freq: snapshot.gpu.as_ref().map(|g| g.cur_freq).unwrap_or(0),
        gpu_max: snapshot.gpu.as_ref().map(|g| g.max_freq).unwrap_or(0),
        gpu_busy: snapshot.gpu.as_ref().map(|g| g.busy_pct).unwrap_or(0),
        gpu_temp: snapshot.gpu.as_ref().and_then(|g| g.gpu_temp_c).unwrap_or(0.0),
        // Battery
        batt_pct: snapshot.battery.capacity,
        batt_temp: snapshot.battery.temp_centi as f64 / 10.0,
        current_ma: snapshot.battery.current_ua / 1000,
        power_mw: snapshot.battery.power_mw,
        // DDR
        ddr_freq: snapshot.ddr.as_ref().map(|d| d.cur_freq).unwrap_or(0),
        ddr_max: snapshot.ddr.as_ref().map(|d| d.max_freq).unwrap_or(0),
        // Thermal
        temp_milli: temp,
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
