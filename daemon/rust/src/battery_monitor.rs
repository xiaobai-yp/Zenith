// battery_monitor.rs — full discharge accounting in daemon.
//
// Sources (all root-readable):
//   - sysfs multi-source fallback: current / voltage / temp / capacity
//   - `dumpsys batterystats --charged` → Screen on/off discharge mAh (3s TTL)
//   - CLOCK_BOOTTIME - CLOCK_MONOTONIC → deep sleep (suspend) time
//   - screen state from brightness sysfs (fed in by caller)
//
// Attribution:
//   - screen on  → active_mah (mAh delta of "Screen on discharge")
//   - screen off → idle_mah   ("Screen off discharge"), split deep/awake by suspend delta
//   - fallback if batterystats unavailable: % step × capacity
//   - charging freezes all accounting (charging_paused)
//
// Persistence: JSON to app files dir (daemon is root).

use serde::{Deserialize, Serialize};
use std::io::Write;
use std::sync::{Mutex, OnceLock};
use tokio::process::Command;

use crate::zen_path;

const STATE_FILE: &str = "/data/data/com.zenith.thermal/files/battery_stats.json";
const BATTERYSTATS_TTL_MS: u64 = 3_000;
const PERSIST_EVERY_MS: u64 = 10_000;
const CAPACITY_FALLBACK_MAH: i64 = 5_000;

// ── public data ──

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct BatteryReadings {
    pub capacity: i32, // percent
    pub current_ma: i32, // absolute mA
    pub voltage_mv: i32,
    pub temp_centi: i64, // °C × 10
    pub power_mw: i64,
    pub charging: bool,
    pub status: String,
    pub capacity_mah: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct ScreenTimes {
    pub screen_on_ms: u64,
    pub screen_off_ms: u64,
    pub active_mah: f64,
    pub idle_mah: f64,
    pub deep_sleep_ms: u64,
    pub awake_ms: u64,
    pub screen_on_pct: u32,
    pub screen_off_pct: u32,
}

#[derive(Debug, Clone, Serialize)]
pub struct DrainRates {
    pub active_drain_pct_per_hr: f64,
    pub idle_drain_pct_per_hr: f64,
}

// ── internal state ──

#[derive(Debug, Clone, Serialize, Deserialize)]
struct AccState {
    screen_on_ms: u64,
    screen_off_ms: u64,
    deep_ms: u64,
    awake_ms: u64,
    active_mah: f64,
    idle_mah: f64,
    screen_on: bool,
    charging_paused: bool,
}

impl Default for AccState {
    fn default() -> Self {
        Self {
            screen_on_ms: 0,
            screen_off_ms: 0,
            deep_ms: 0,
            awake_ms: 0,
            active_mah: 0.0,
            idle_mah: 0.0,
            screen_on: false,
            charging_paused: false,
        }
    }
}

struct Inner {
    acc: AccState,
    readings: BatteryReadings,
    last_elapsed_ms: u64,
    last_uptime_ms: u64,
    last_batt_on_mah: f64, // -1 = unavailable
    last_batt_off_mah: f64,
    battstats_ok: bool,
    screen_on_start_pct: i32, // -1 = unset
    screen_off_start_pct: i32,
    last_batterystats_ms: u64,
    last_persist_ms: u64,
}

static STATE: OnceLock<Mutex<Inner>> = OnceLock::new();

fn lock() -> std::sync::MutexGuard<'static, Inner> {
    STATE.get().expect("battery_monitor not initialized").lock().unwrap()
}

// ── clocks ──

fn clock_ms(clock: libc::clockid_t) -> u64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { libc::clock_gettime(clock, &mut ts) };
    (ts.tv_sec as u64) * 1000 + (ts.tv_nsec as u64) / 1_000_000
}

/// elapsedRealtime proxy — advances during suspend.
fn elapsed_ms() -> u64 {
    clock_ms(libc::CLOCK_BOOTTIME)
}

/// uptimeMillis proxy — frozen during suspend.
fn uptime_ms() -> u64 {
    clock_ms(libc::CLOCK_MONOTONIC)
}

// ── sysfs multi-source reads ──

async fn read_first(paths: &[&str]) -> Option<i64> {
    for p in paths {
        if let Ok(v) = tokio::fs::read_to_string(p).await {
            if let Ok(n) = v.trim().parse::<i64>() {
                return Some(n);
            }
        }
    }
    None
}

async fn read_current_ma() -> i32 {
    let raw = read_first(&[
        zen_path!("/sys/class/power_supply/battery/current_now"),
        zen_path!("/sys/class/power_supply/bms/current_now"),
        zen_path!("/sys/class/power_supply/main_battery/current_now"),
    ])
    .await;
    match raw {
        Some(v) => {
            let ma = if v.abs() < 10_000 { v } else { v / 1000 };
            ma.abs() as i32
        }
        None => 0,
    }
}

async fn read_voltage_mv() -> i32 {
    let raw = read_first(&[
        zen_path!("/sys/class/power_supply/battery/voltage_now"),
        zen_path!("/sys/class/power_supply/bms/voltage_now"),
        zen_path!("/sys/class/power_supply/main_battery/voltage_now"),
        zen_path!("/sys/class/power_supply/usb/voltage_now"),
        zen_path!("/sys/class/power_supply/wireless/voltage_now"),
    ])
    .await;
    match raw {
        Some(v) => {
            if v >= 100_000 { (v / 1000) as i32 } else { v as i32 }
        }
        None => 0,
    }
}

async fn read_temp_centi() -> i64 {
    let raw = read_first(&[
        zen_path!("/sys/class/power_supply/battery/temp"),
        zen_path!("/sys/class/power_supply/bms/temp"),
        zen_path!("/sys/class/power_supply/main_battery/temp"),
        zen_path!("/sys/class/power_supply/battery/battery_temp"),
        zen_path!("/sys/class/power_supply/battery/temperature"),
        zen_path!("/sys/class/power_supply/bms/temp_now"),
    ])
    .await;
    match raw {
        Some(v) => {
            if v >= 10_000 { v / 100 } else { v } // milli°C → centi, else already centi
        }
        None => 0,
    }
}

async fn read_capacity_mah() -> i64 {
    let raw = read_first(&[
        zen_path!("/sys/class/power_supply/battery/charge_full_design"),
        zen_path!("/sys/class/power_supply/battery/charge_full"),
        zen_path!("/sys/class/power_supply/bms/charge_full_design"),
        zen_path!("/sys/class/power_supply/bms/charge_full"),
    ])
    .await;
    match raw {
        Some(v) => {
            if v >= 100_000 { v / 1000 } else { v }
        }
        None => CAPACITY_FALLBACK_MAH,
    }
}

async fn read_capacity_pct() -> i32 {
    read_first(&[
        zen_path!("/sys/class/power_supply/battery/capacity"),
        zen_path!("/sys/class/power_supply/bms/capacity"),
    ])
    .await
    .unwrap_or(0) as i32
}

async fn read_charging() -> (bool, String) {
    let mut s = String::new();
    for p in [
        zen_path!("/sys/class/power_supply/battery/status"),
        zen_path!("/sys/class/power_supply/bms/status"),
    ] {
        if let Ok(v) = tokio::fs::read_to_string(p).await {
            s = v.trim().to_string();
            break;
        }
    }
    let charging = s == "Charging" || s == "Full";
    (charging, s)
}

// ── batterystats ──

async fn run_batterystats() -> Option<String> {
    // daemon is root: try direct first, fall back to su.
    let direct = Command::new(zen_path!("/system/bin/dumpsys"))
        .args(["batterystats", "--charged"])
        .output()
        .await;
    if let Ok(out) = direct {
        if out.status.success() {
            return Some(String::from_utf8_lossy(&out.stdout).to_string());
        }
    }
    let via_su = Command::new(zen_path!("/system/bin/su"))
        .args(["-c", "dumpsys batterystats --charged"])
        .output()
        .await;
    match via_su {
        Ok(out) if out.status.success() => Some(String::from_utf8_lossy(&out.stdout).to_string()),
        _ => None,
    }
}

fn parse_mah_line(text: &str, needle: &str) -> Option<f64> {
    let line = text.lines().find(|l| l.contains(needle))?;
    let val = line.split(':').nth(1)?.trim();
    let num: String = val.chars().take_while(|c| c.is_ascii_digit() || *c == '.').collect();
    num.parse().ok()
}

/// Refreshes batterystats values if TTL expired. Returns (on_mah, off_mah) options.
async fn refresh_batterystats() -> (Option<f64>, Option<f64>) {
    {
        let st = lock();
        if !st.battstats_ok && st.last_batterystats_ms > 0 {
            // previously failed within TTL — still return cached -1
        }
        if st.last_batterystats_ms != 0
            && elapsed_ms().saturating_sub(st.last_batterystats_ms) < BATTERYSTATS_TTL_MS
        {
            return (None, None); // cached, no refresh
        }
    }
    let start = elapsed_ms();
    let text = match tokio::time::timeout(
        std::time::Duration::from_millis(1500),
        run_batterystats(),
    )
    .await
    {
        Ok(Some(t)) => t,
        _ => return (None, None),
    };
    let on = parse_mah_line(&text, "Screen on discharge:");
    let off = parse_mah_line(&text, "Screen off discharge:");
    let mut st = lock();
    st.last_batterystats_ms = start;
    if on.is_some() || off.is_some() {
        st.battstats_ok = true;
        st.last_batt_on_mah = on.unwrap_or(st.last_batt_on_mah);
        st.last_batt_off_mah = off.unwrap_or(st.last_batt_off_mah);
    }
    (on, off)
}

// ── persistence ──

async fn persist() {
    let data = {
        let st = lock();
        serde_json::to_vec(&st.acc).unwrap_or_default()
    };
    let _ = tokio::fs::write(STATE_FILE, &data).await;
    st_lastpersist_update();
}

fn st_lastpersist_update() {
    let mut st = lock();
    st.last_persist_ms = elapsed_ms();
}

// ── public API ──

pub async fn init() {
    let mut acc = AccState::default();
    if let Ok(text) = tokio::fs::read_to_string(STATE_FILE).await {
        if let Ok(x) = serde_json::from_str::<AccState>(&text) {
            acc = x;
        }
    }
    let now = elapsed_ms();
    let inner = Inner {
        acc,
        readings: BatteryReadings::default(),
        last_elapsed_ms: now,
        last_uptime_ms: uptime_ms(),
        last_batt_on_mah: -1.0,
        last_batt_off_mah: -1.0,
        battstats_ok: false,
        screen_on_start_pct: -1,
        screen_off_start_pct: -1,
        last_batterystats_ms: 0,
        last_persist_ms: now,
    };
    let _ = STATE.set(Mutex::new(inner));
    // Seed batterystats markers.
    refresh_batterystats().await;
    // Seed initial readings.
    update_sensors().await;
    crate::log!("[battery_monitor] init: loaded acc {:?}", lock().acc.screen_on_ms > 0 || lock().acc.screen_off_ms > 0);
}

async fn update_sensors() {
    let voltage_mv = read_voltage_mv().await;
    let current_ma = read_current_ma().await;
    let (charging, status) = read_charging().await;
    let readings = BatteryReadings {
        capacity: read_capacity_pct().await,
        current_ma,
        voltage_mv,
        temp_centi: read_temp_centi().await,
        power_mw: (voltage_mv as i64) * (current_ma as i64) / 1000,
        charging,
        status,
        capacity_mah: read_capacity_mah().await,
    };
    let mut st = lock();
    st.readings = readings;
}

/// Main 1s tick. `screen_on` from brightness snapshot.
pub async fn update(screen_on: bool) {
    update_sensors().await;
    let (d_on, d_off) = refresh_batterystats().await;
    let cap_mah = { lock().readings.capacity_mah };

    let e = elapsed_ms();
    let u = uptime_ms();
    let mut st = lock();

    let de = e.saturating_sub(st.last_elapsed_ms);
    let du = u.saturating_sub(st.last_uptime_ms);
    st.last_elapsed_ms = e;
    st.last_uptime_ms = u;
    let suspend = de.saturating_sub(du);

    let charging = st.readings.charging;

    // state transition: charging ↔ discharging
    if charging && !st.acc.charging_paused {
        st.acc.charging_paused = true; // freeze accounting
        crate::log!("[battery_monitor] charging started — accounting paused");
        let _ = persist_maybe(&mut st);
    } else if !charging && st.acc.charging_paused {
        st.acc.charging_paused = false;
        crate::log!("[battery_monitor] charging ended — accounting resumed");
        st.screen_on_start_pct = st.readings.capacity;
        st.screen_off_start_pct = st.readings.capacity;
        let _ = persist_maybe(&mut st);
    }

    // mAh attribution — % step is PRIMARY (monotonic, always available).
    // batterystats deltas were unreliable on this device: the stats reset
    // on every unplug, so accumulated deltas stall at 0. We keep the
    // batterystats read for baseline only — no accumulation from it.
    if !st.acc.charging_paused {
        // re-baseline batterystats markers (no accumulation — % step owns mAh)
        st.last_batt_on_mah = d_on.unwrap_or(st.last_batt_on_mah);
        st.last_batt_off_mah = d_off.unwrap_or(st.last_batt_off_mah);

        // %-step attribution — always on
        {
            let pct = st.readings.capacity;
            step_attribution(&mut st, screen_on, pct, cap_mah);
        }

        // time attribution
        if screen_on {
            st.acc.screen_on_ms += de;
        } else {
            st.acc.screen_off_ms += de;
            st.acc.deep_ms += suspend;
            st.acc.awake_ms += de.saturating_sub(suspend);
        }
    } else {
        // keep start markers fresh so a huge gap is not attributed on resume
        st.last_batt_on_mah = d_on.unwrap_or(st.last_batt_on_mah);
        st.last_batt_off_mah = d_off.unwrap_or(st.last_batt_off_mah);
        st.screen_on_start_pct = st.readings.capacity;
        st.screen_off_start_pct = st.readings.capacity;
    }

    // screen transition bookkeeping for % fallback
    if screen_on != st.acc.screen_on {
        st.acc.screen_on = screen_on;
        if screen_on {
            st.screen_on_start_pct = st.readings.capacity;
        } else {
            st.screen_off_start_pct = st.readings.capacity;
        }
    }

    let _ = persist_maybe(&mut st);
}

fn step_attribution(st: &mut Inner, screen_on: bool, pct: i32, cap_mah: i64) {
    if screen_on {
        if st.screen_on_start_pct > 0 {
            let used = st.screen_on_start_pct.saturating_sub(pct);
            st.acc.active_mah += used as f64 * cap_mah as f64 / 100.0;
            st.screen_on_start_pct = pct;
        }
    } else {
        if st.screen_off_start_pct > 0 {
            let used = st.screen_off_start_pct.saturating_sub(pct);
            st.acc.idle_mah += used as f64 * cap_mah as f64 / 100.0;
            st.screen_off_start_pct = pct;
        }
    }
}

fn persist_maybe(st: &mut Inner) -> Result<(), ()> {
    let now = elapsed_ms();
    let delta = now.saturating_sub(st.last_persist_ms);
    crate::log!("[battery_monitor] persist_maybe: now={}, last={}, delta={}, target={}", now, st.last_persist_ms, delta, PERSIST_EVERY_MS);
    if delta >= PERSIST_EVERY_MS {
        st.last_persist_ms = now;
        let data = serde_json::to_vec(&st.acc).unwrap_or_default();
        match std::fs::write(STATE_FILE, &data) {
            Ok(()) => crate::log!("[battery_monitor] persisted {} bytes to {}", data.len(), STATE_FILE),
            Err(e) => crate::log!("[battery_monitor] persist FAILED: {} (path={})", e, STATE_FILE),
        }
    }
    Ok(())
}

pub fn reset() {
    let mut st = lock();
    st.acc = AccState::default();
    st.acc.screen_on = st.readings.charging; // if charging, stays paused
    st.acc.charging_paused = st.readings.charging;
    st.screen_on_start_pct = st.readings.capacity;
    st.screen_off_start_pct = st.readings.capacity;
    st.last_batt_on_mah = -1.0;
    st.last_batt_off_mah = -1.0;
    st.battstats_ok = false;
    st.last_batterystats_ms = 0;
    let data = serde_json::to_vec(&st.acc).unwrap_or_default();
    let _ = std::fs::write(STATE_FILE, &data);
}

pub fn readings() -> BatteryReadings {
    lock().readings.clone()
}

pub fn get_screen_times() -> ScreenTimes {
    let st = lock();
    let cap = st.readings.capacity_mah.max(1) as f64;
    let on_pct = if cap > 0.0 { (st.acc.active_mah / cap * 100.0).round() as u32 } else { 0 };
    let off_pct = if cap > 0.0 { (st.acc.idle_mah / cap * 100.0).round() as u32 } else { 0 };
    ScreenTimes {
        screen_on_ms: st.acc.screen_on_ms,
        screen_off_ms: st.acc.screen_off_ms,
        active_mah: st.acc.active_mah,
        idle_mah: st.acc.idle_mah,
        deep_sleep_ms: st.acc.deep_ms,
        awake_ms: st.acc.awake_ms,
        screen_on_pct: on_pct,
        screen_off_pct: off_pct,
    }
}

pub fn get_drain_rates() -> DrainRates {
    let t = get_screen_times();
    let cap = t.active_mah + t.idle_mah;
    let on_h = t.screen_on_ms as f64 / 3_600_000.0;
    let off_h = t.screen_off_ms as f64 / 3_600_000.0;
    DrainRates {
        active_drain_pct_per_hr: if on_h > 0.01 {
            t.active_mah / cap.max(0.001) * 100.0 / on_h
        } else {
            0.0
        },
        idle_drain_pct_per_hr: if off_h > 0.01 {
            t.idle_mah / cap.max(0.001) * 100.0 / off_h
        } else {
            0.0
        },
    }
}

