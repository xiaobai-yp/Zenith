// battery_monitor.rs — Drain rate tracking with EMA, screen-on/off split.

use serde::Serialize;
use std::collections::VecDeque;
use std::sync::{OnceLock, RwLock};

const SAMPLE_BUF_SIZE: usize = 16;
const EMA_ALPHA: f64 = 0.3; // 0.3 * new + 0.7 * old
const IDLE_CURRENT_UA: i64 = 100_000; // 100 mA in µA

#[derive(Debug, Clone, Copy)]
struct Sample {
    capacity: i32,
    current_ua: i64,
    online: bool,
    timestamp_ms: u64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct BatteryStats {
    pub avg_drain_ma: f64,
    pub avg_drain_screen_on_ma: f64,
    pub avg_drain_screen_off_ma: f64,
    pub idle_drain_ma: f64,
    pub sample_count: usize,
    pub last_capacity: i32,
    pub estimated_hours_left: f64,
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
    screen_on: bool, // simplified: track via last current draw
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
}

pub fn update(
    capacity: i32,
    current_ua: i64,
    _voltage_uv: i64,
    online: bool,
    timestamp_ms: u64,
) {
    let mut s = state().write().unwrap();

    let current_ma = current_ua.abs() as f64 / 1000.0;
    let is_screen_on = current_ma > 100.0; // heuristic
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
        let dt_hours = (newest.timestamp_ms as f64 - oldest.timestamp_ms as f64)
            / 3_600_000.0;
        if dt_hours > 0.01 {
            let capacity_drop = oldest.capacity as f64 - newest.capacity as f64;
            let drain_rate = (capacity_drop / dt_hours * 40.0).max(0.0); // assume ~4000mAh

            if !s.initialized_ema {
                s.screen_on_drain = drain_rate;
                s.screen_off_drain = drain_rate;
                s.idle_drain = drain_rate;
                s.initialized_ema = true;
            } else if is_screen_on {
                s.screen_on_drain = EMA_ALPHA * drain_rate + (1.0 - EMA_ALPHA) * s.screen_on_drain;
                s.screen_on_seen = true;
            } else {
                s.screen_off_drain = EMA_ALPHA * drain_rate + (1.0 - EMA_ALPHA) * s.screen_off_drain;
                s.screen_off_seen = true;
            }

            // Idle drain: when current < 100mA
            if current_ua.abs() < IDLE_CURRENT_UA {
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

    let hours_left = if avg_drain > 0.5 {
        s.last_capacity as f64 / avg_drain * 40.0 // capacity% → hours
    } else {
        0.0
    };

    BatteryStats {
        avg_drain_ma: avg_drain,
        avg_drain_screen_on_ma: s.screen_on_drain,
        avg_drain_screen_off_ma: s.screen_off_drain,
        idle_drain_ma: s.idle_drain,
        sample_count: s.samples.len(),
        last_capacity: s.last_capacity,
        estimated_hours_left: hours_left,
    }
}
