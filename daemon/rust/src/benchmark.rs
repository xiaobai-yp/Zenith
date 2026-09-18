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
    // Frame timing (Scene)
    pub jank: i32,
    pub big_jank: i32,
    pub max_frame_time_ms: i32,
    // CPU
    pub cpu_load: f64,        // total CPU load %
    pub cpu0_freq: u32,       // policy0 current freq kHz
    pub cpu0_max: u32,
    pub cpu1_freq: u32,
    pub cpu1_max: u32,
    pub cpu2_freq: u32,
    pub cpu2_max: u32,
    pub cpu3_freq: u32,
    pub cpu3_max: u32,
    pub cpu4_freq: u32,       // policy4 current freq kHz
    pub cpu4_max: u32,
    pub cpu5_freq: u32,
    pub cpu5_max: u32,
    pub cpu6_freq: u32,
    pub cpu6_max: u32,
    pub cpu7_freq: u32,       // policy7 current freq kHz
    pub cpu7_max: u32,
    // CPU cycles (from perf_event, millions)
    pub cpu0_cycles: u64,
    pub cpu1_cycles: u64,
    pub cpu2_cycles: u64,
    pub cpu3_cycles: u64,
    pub cpu4_cycles: u64,
    pub cpu5_cycles: u64,
    pub cpu6_cycles: u64,
    pub cpu7_cycles: u64,
    // GPU
    pub gpu_freq: i64,        // GPU current freq Hz
    pub gpu_max: i64,
    pub gpu_busy: i32,        // GPU busy %
    pub gpu_temp: f64,
    // Battery / Power
    pub batt_pct: i32,
    pub batt_temp: f64,
    pub current_ma: i64,
    pub voltage_mv: i64,      // battery voltage in mV
    pub power_mw: i64,
    // DDR
    pub ddr_freq: u32,
    pub ddr_max: u32,
    // Thermal
    pub temp_milli: i64,
    // Thread stats (tid, percent, cpus)
    pub thread_usage: Vec<ThreadUsage>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThreadUsage {
    pub tid: u32,
    pub percent: f32,
    pub cpus: String,  // comma-separated CPU list
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

    // Read jank/frame timing from fps_monitor
    let (_, jank, big_jank, max_frame_time_ms) = fps_monitor::read_jank();

    // Read per-core frequencies from sysfs (policy0=0-3, policy4=4-6, policy7=7)
    let cpu1_freq = read_cpu_freq(1);
    let cpu2_freq = read_cpu_freq(2);
    let cpu3_freq = read_cpu_freq(3);
    let cpu5_freq = read_cpu_freq(5);
    let cpu6_freq = read_cpu_freq(6);

    // CPU cycles from perf_event (placeholder for now)
    let cycles = read_cpu_cycles().unwrap_or([0u64; 8]);

    // Thread usage from top threads
    let thread_usage = read_thread_usage();

    let point = BenchPoint {
        timestamp_ms: now_ms(),
        fps,
        fps_short,
        fps_avg,
        // Frame timing (Scene)
        jank,
        big_jank,
        max_frame_time_ms,
        // CPU
        cpu_load: snapshot.cpu_load_pct,
        cpu0_freq: snapshot.cpu_policy0.cur_freq,
        cpu0_max: snapshot.cpu_policy0.max_freq,
        cpu1_freq,
        cpu1_max: snapshot.cpu_policy0.max_freq,
        cpu2_freq,
        cpu2_max: snapshot.cpu_policy0.max_freq,
        cpu3_freq,
        cpu3_max: snapshot.cpu_policy0.max_freq,
        cpu4_freq: snapshot.cpu_policy4.as_ref().map(|c| c.cur_freq).unwrap_or(0),
        cpu4_max: snapshot.cpu_policy4.as_ref().map(|c| c.max_freq).unwrap_or(0),
        cpu5_freq,
        cpu5_max: snapshot.cpu_policy4.as_ref().map(|c| c.max_freq).unwrap_or(0),
        cpu6_freq,
        cpu6_max: snapshot.cpu_policy4.as_ref().map(|c| c.max_freq).unwrap_or(0),
        cpu7_freq: snapshot.cpu_policy7.as_ref().map(|c| c.cur_freq).unwrap_or(0),
        cpu7_max: snapshot.cpu_policy7.as_ref().map(|c| c.max_freq).unwrap_or(0),
        // CPU cycles (millions)
        cpu0_cycles: cycles[0],
        cpu1_cycles: cycles[1],
        cpu2_cycles: cycles[2],
        cpu3_cycles: cycles[3],
        cpu4_cycles: cycles[4],
        cpu5_cycles: cycles[5],
        cpu6_cycles: cycles[6],
        cpu7_cycles: cycles[7],
        // GPU
        gpu_freq: snapshot.gpu.as_ref().map(|g| g.cur_freq).unwrap_or(0),
        gpu_max: snapshot.gpu.as_ref().map(|g| g.max_freq).unwrap_or(0),
        gpu_busy: snapshot.gpu.as_ref().map(|g| g.busy_pct).unwrap_or(0),
        gpu_temp: snapshot.gpu.as_ref().and_then(|g| g.gpu_temp_c).unwrap_or(0.0),
        // Battery
        batt_pct: snapshot.battery.capacity,
        batt_temp: snapshot.battery.temp_centi as f64 / 10.0,
        current_ma: snapshot.battery.current_ua / 1000,
        voltage_mv: snapshot.battery.voltage_uv / 1000,  // uV -> mV
        power_mw: snapshot.battery.power_mw,
        // DDR
        ddr_freq: snapshot.ddr.as_ref().map(|d| d.cur_freq).unwrap_or(0),
        ddr_max: snapshot.ddr.as_ref().map(|d| d.max_freq).unwrap_or(0),
        // Thermal
        temp_milli: temp,
        // Thread stats
        thread_usage,
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

/// Read individual CPU frequency from sysfs
fn read_cpu_freq(cpu: usize) -> u32 {
    let path = format!("/sys/devices/system/cpu/cpu{cpu}/cpufreq/scaling_cur_freq");
    std::fs::read_to_string(&path)
        .ok()
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or(0)
}

/// Read CPU cycles from perf_event (placeholder - requires perf_event_open or sysfs)
fn read_cpu_cycles() -> Option<[u64; 8]> {
    // This would read from perf_event_open or /sys/bus/event_source/devices/cpu/...
    // For now return None to use placeholder zeros
    None
}

/// Read top thread usage from /proc/<pid>/task/<tid>/stat
fn read_thread_usage() -> Vec<ThreadUsage> {
    // Placeholder - would need to parse /proc/<pid>/task/ for foreground app
    vec![]
}
