// battery_monitor.rs — Drain rate tracking with EMA, screen-on/off split.
// Persistent stats via battery_session.json.

use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::io::Write;
use std::sync::{OnceLock, RwLock};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

const SAMPLE_BUF_SIZE: usize = 16;
const EMA_ALPHA: f64 = 0.3; // 0.3 * new + 0.7 * old
const SESSION_PATH: &str = "/data/data/com.zenith.thermal/files/battery_session.json";

#[derive(Debug, Clone, Copy)]
struct Sample {
    capacity: i32,
    current_ua: i64,
    online: bool,
    timestamp_ms: u64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct BatteryStats {
    pub drain_pct_per_hr: f64,
    pub screen_on_drain_pct_per_hr: f64,
    pub screen_off_drain_pct_per_hr: f64,
    pub idle_drain_pct_per_hr: f64,
    pub sample_count: usize,
    pub last_capacity: i32,
}

struct MonitorState {
    samples: VecDeque<Sample>,
    screen_on_drain: f64,
    screen_off_drain: f64,
    idle_drain: f64,
    initialized_ema: bool,
    screen_on_seen: bool,
    screen_off_seen: bool,
    last_capacity: i32,
    screen_on: bool,
}

static STATE: OnceLock<RwLock<MonitorState>> = OnceLock::new();

fn state() -> &'static RwLock<MonitorState> {
    STATE.get().expect("battery_monitor not initialized")
}

pub fn init() {
    let _ = STATE.set(RwLock::new(MonitorState {
        samples: VecDeque::with_capacity(SAMPLE_BUF_SIZE),
        screen_on_drain: 0.0,
        screen_off_drain: 0.0,
        idle_drain: 0.0,
        initialized_ema: false,
        screen_on_seen: false,
        screen_off_seen: false,
        last_capacity: 0,
        screen_on: true,
    }));
    // Try restore from persistent session
    restore_session();
}

pub fn update(
    capacity: i32,
    current_ua: i64,
    _voltage_uv: i64,
    online: bool,
    timestamp_ms: u64,
    screen_on: bool,
) {
    let mut s = state().write().unwrap();

    let is_screen_on = screen_on;
    s.screen_on = is_screen_on;

    let sample = Sample {
        capacity,
        current_ua,
        online,
        timestamp_ms,
    };

    if s.samples.len() >= SAMPLE_BUF_SIZE {
        s.samples.pop_front();
    }
    s.samples.push_back(sample);

    s.last_capacity = capacity;

    // Compute drain rate from capacity delta
    if s.samples.len() >= 2 {
        let oldest = s.samples.front().unwrap();
        let newest = s.samples.back().unwrap();
        let dt_hours = (newest.timestamp_ms as f64 - oldest.timestamp_ms as f64) / 3_600_000.0;
        if dt_hours > 0.01 {
            let capacity_drop = oldest.capacity as f64 - newest.capacity as f64;
            let drain_rate = (capacity_drop / dt_hours).max(0.0); // %/hr

            if !s.initialized_ema {
                s.screen_on_drain = drain_rate;
                s.screen_off_drain = drain_rate;
                s.idle_drain = drain_rate;
                s.initialized_ema = true;
            } else if is_screen_on {
                s.screen_on_drain = EMA_ALPHA * drain_rate + (1.0 - EMA_ALPHA) * s.screen_on_drain;
                s.screen_on_seen = true;
            } else {
                s.screen_off_drain =
                    EMA_ALPHA * drain_rate + (1.0 - EMA_ALPHA) * s.screen_off_drain;
                s.screen_off_seen = true;
                // Idle drain = drain during screen-off (per spec)
                s.idle_drain = EMA_ALPHA * drain_rate + (1.0 - EMA_ALPHA) * s.idle_drain;
            }
        }
    }
}

pub fn get_stats() -> BatteryStats {
    let s = state().read().unwrap();
    let avg_drain = if s.screen_on_seen && s.screen_off_seen {
        (s.screen_on_drain + s.screen_off_drain) / 2.0
    } else if s.screen_on_seen {
        s.screen_on_drain
    } else if s.screen_off_seen {
        s.screen_off_drain
    } else {
        0.0
    };

    BatteryStats {
        drain_pct_per_hr: avg_drain,
        screen_on_drain_pct_per_hr: s.screen_on_drain,
        screen_off_drain_pct_per_hr: s.screen_off_drain,
        idle_drain_pct_per_hr: s.idle_drain,
        sample_count: s.samples.len(),
        last_capacity: s.last_capacity,
    }
}

// ── Screen time tracking ──

static SCREEN_ON_ACCUM_MS: AtomicU64 = AtomicU64::new(0);
static SCREEN_OFF_ACCUM_MS: AtomicU64 = AtomicU64::new(0);
static DEEP_SLEEP_ACCUM_MS: AtomicU64 = AtomicU64::new(0);
static AWAKE_ACCUM_MS: AtomicU64 = AtomicU64::new(0);
static TOTAL_MS: AtomicU64 = AtomicU64::new(0);
static LAST_SCREEN_STATE: AtomicBool = AtomicBool::new(true);
static LAST_UPDATE_MS: AtomicU64 = AtomicU64::new(0);

pub fn reset_times() {
    SCREEN_ON_ACCUM_MS.store(0, Ordering::Relaxed);
    SCREEN_OFF_ACCUM_MS.store(0, Ordering::Relaxed);
    DEEP_SLEEP_ACCUM_MS.store(0, Ordering::Relaxed);
    AWAKE_ACCUM_MS.store(0, Ordering::Relaxed);
    TOTAL_MS.store(0, Ordering::Relaxed);
    LAST_UPDATE_MS.store(0, Ordering::Relaxed);
}

pub fn update_screen_times(now_ms: u64, screen_on: bool, current_ua: i64) {
    let last = LAST_UPDATE_MS.swap(now_ms, Ordering::Relaxed);
    if last == 0 {
        LAST_SCREEN_STATE.store(screen_on, Ordering::Relaxed);
        return;
    }
    let dt = now_ms.saturating_sub(last);
    let was_on = LAST_SCREEN_STATE.swap(screen_on, Ordering::Relaxed);

    if was_on {
        SCREEN_ON_ACCUM_MS.fetch_add(dt, Ordering::Relaxed);
    } else {
        SCREEN_OFF_ACCUM_MS.fetch_add(dt, Ordering::Relaxed);
    }

    // Deep sleep: screen off AND current < 50mA (device sleeping)
    if !screen_on && current_ua.abs() < 50_000 {
        DEEP_SLEEP_ACCUM_MS.fetch_add(dt, Ordering::Relaxed);
    }
    // Awake: current > 50mA (device actively drawing)
    if current_ua.abs() > 50_000 {
        AWAKE_ACCUM_MS.fetch_add(dt, Ordering::Relaxed);
    }
    TOTAL_MS.fetch_add(dt, Ordering::Relaxed);
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct ScreenTimes {
    pub screen_on_ms: u64,
    pub screen_off_ms: u64,
    pub deep_sleep_ms: u64,
    pub awake_ms: u64,
    pub total_ms: u64,
}

pub fn get_screen_times() -> ScreenTimes {
    ScreenTimes {
        screen_on_ms: SCREEN_ON_ACCUM_MS.load(Ordering::Relaxed),
        screen_off_ms: SCREEN_OFF_ACCUM_MS.load(Ordering::Relaxed),
        deep_sleep_ms: DEEP_SLEEP_ACCUM_MS.load(Ordering::Relaxed),
        awake_ms: AWAKE_ACCUM_MS.load(Ordering::Relaxed),
        total_ms: TOTAL_MS.load(Ordering::Relaxed),
    }
}

// ── Persistent session storage ──

#[derive(Serialize, Deserialize, Default)]
struct SessionData {
    screen_on_ms: u64,
    screen_off_ms: u64,
    deep_sleep_ms: u64,
    awake_ms: u64,
    total_ms: u64,
    active_drain: f64,
    idle_drain: f64,
}

pub fn save_session() {
    let times = get_screen_times();
    let stats = get_stats();
    let data = SessionData {
        screen_on_ms: times.screen_on_ms,
        screen_off_ms: times.screen_off_ms,
        deep_sleep_ms: times.deep_sleep_ms,
        awake_ms: times.awake_ms,
        total_ms: times.total_ms,
        active_drain: stats.screen_on_drain_pct_per_hr,
        idle_drain: stats.idle_drain_pct_per_hr,
    };
    if let Ok(json) = serde_json::to_string(&data) {
        let _ = std::fs::write(SESSION_PATH, json);
    }
}

fn restore_session() {
    let Ok(content) = std::fs::read_to_string(SESSION_PATH) else {
        return;
    };
    let Ok(data) = serde_json::from_str::<SessionData>(&content) else {
        return;
    };
    SCREEN_ON_ACCUM_MS.store(data.screen_on_ms, Ordering::Relaxed);
    SCREEN_OFF_ACCUM_MS.store(data.screen_off_ms, Ordering::Relaxed);
    DEEP_SLEEP_ACCUM_MS.store(data.deep_sleep_ms, Ordering::Relaxed);
    AWAKE_ACCUM_MS.store(data.awake_ms, Ordering::Relaxed);
    TOTAL_MS.store(data.total_ms, Ordering::Relaxed);
    crate::log!(
        "[battery_monitor] restored session: on={}ms off={}ms",
        data.screen_on_ms, data.screen_off_ms
    );
}
