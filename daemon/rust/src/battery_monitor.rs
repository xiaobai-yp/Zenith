use std::io::Write;

/// Screen time tracking with battery consumption attribution
/// and deep sleep / awake power state residency.
///
/// Screen ON/OFF: tracks battery consumed during each state.
/// Deep Sleep/Awake: tracks time residency (current heuristic: screen off + low current).
///
/// Drain rate = battery consumed during active/idle time / hours.

struct ScreenState {
    screen_on_duration_ms: u64,
    screen_off_duration_ms: u64,
    screen_on_battery_used: u32,
    screen_off_battery_used: u32,
    deep_sleep_ms: u64,
    awake_ms: u64,
    last_update_ms: u64,
    last_sleep_update_ms: u64,
    screen_on_start_battery: i32,
    screen_off_start_battery: i32,
    current_battery: i32,
    is_screen_on: bool,
    is_deep_sleep: bool,
}

static STATE: once_cell::sync::Lazy<std::sync::Mutex<ScreenState>> =
    once_cell::sync::Lazy::new(|| {
        std::sync::Mutex::new(ScreenState {
            screen_on_duration_ms: 0,
            screen_off_duration_ms: 0,
            screen_on_battery_used: 0,
            screen_off_battery_used: 0,
            deep_sleep_ms: 0,
            awake_ms: 0,
            last_update_ms: 0,
            last_sleep_update_ms: 0,
            screen_on_start_battery: 0,
            screen_off_start_battery: 0,
            current_battery: 0,
            is_screen_on: false,
            is_deep_sleep: false,
        })
    });

pub fn init() {
    let mut state = STATE.lock().unwrap();
    *state = ScreenState {
        screen_on_duration_ms: 0,
        screen_off_duration_ms: 0,
        screen_on_battery_used: 0,
        screen_off_battery_used: 0,
        deep_sleep_ms: 0,
        awake_ms: 0,
        last_update_ms: 0,
        last_sleep_update_ms: 0,
        screen_on_start_battery: 0,
        screen_off_start_battery: 0,
        current_battery: 0,
        is_screen_on: false,
        is_deep_sleep: false,
    };
}

/// Update screen state and battery levels.
/// `current_ua`: raw microamps (for deep sleep heuristic).
pub fn update(screen_on: bool, battery_level: i32, current_ua: i64) {
    let now = current_ms();
    let mut state = STATE.lock().unwrap();

    if state.last_update_ms == 0 {
        state.last_update_ms = now;
        state.last_sleep_update_ms = now;
        state.screen_on_start_battery = battery_level;
        state.screen_off_start_battery = battery_level;
        state.current_battery = battery_level;
        state.is_screen_on = screen_on;
        state.is_deep_sleep = !screen_on && current_ua.abs() < 20_000;
        return;
    }

    let elapsed = now.saturating_sub(state.last_update_ms);
    if elapsed == 0 {
        return;
    }

    // Attribute elapsed time to current screen state
    if state.is_screen_on {
        state.screen_on_duration_ms += elapsed;
    } else {
        state.screen_off_duration_ms += elapsed;
    }

    // Deep sleep / awake
    let deep_sleep = !screen_on && current_ua.abs() < 20_000;
    let sleep_elapsed = now.saturating_sub(state.last_sleep_update_ms);
    if sleep_elapsed > 0 {
        if state.is_deep_sleep {
            state.deep_sleep_ms += sleep_elapsed;
        } else {
            state.awake_ms += sleep_elapsed;
        }
        state.last_sleep_update_ms = now;
    }

    // Handle screen state transition
    if screen_on != state.is_screen_on {
        if screen_on {
            // Transition to ON: close out OFF period
            let used = state.screen_off_start_battery.saturating_sub(battery_level) as u32;
            state.screen_off_battery_used += used;
            state.screen_on_start_battery = battery_level;
        } else {
            // Transition to OFF: close out ON period
            let used = state.screen_on_start_battery.saturating_sub(battery_level) as u32;
            state.screen_on_battery_used += used;
            state.screen_off_start_battery = battery_level;
        }
        state.is_screen_on = screen_on;
    }

    state.current_battery = battery_level;
    state.is_deep_sleep = deep_sleep;
    state.last_update_ms = now;
}

pub fn reset() {
    let mut state = STATE.lock().unwrap();
    *state = ScreenState {
        screen_on_duration_ms: 0,
        screen_off_duration_ms: 0,
        screen_on_battery_used: 0,
        screen_off_battery_used: 0,
        deep_sleep_ms: 0,
        awake_ms: 0,
        last_update_ms: 0,
        last_sleep_update_ms: 0,
        screen_on_start_battery: 0,
        screen_off_start_battery: 0,
        current_battery: 0,
        is_screen_on: false,
        is_deep_sleep: false,
    };
}

pub struct ScreenTimes {
    pub screen_on_ms: u64,
    pub screen_off_ms: u64,
    pub screen_on_battery_used: u32,
    pub screen_off_battery_used: u32,
    pub deep_sleep_ms: u64,
    pub awake_ms: u64,
}

pub fn get_screen_times() -> ScreenTimes {
    let state = STATE.lock().unwrap();
    // Live elapsed so time moves in real-time
    let now = current_ms();
    let live = now.saturating_sub(state.last_update_ms);
    let on_ms = state.screen_on_duration_ms + if state.is_screen_on { live } else { 0 };
    let off_ms = state.screen_off_duration_ms + if !state.is_screen_on { live } else { 0 };

    // Live battery consumed in current state (not yet committed on transition)
    let on_used = state.screen_on_battery_used
        + if state.is_screen_on && state.screen_on_start_battery > 0 {
            state.screen_on_start_battery.saturating_sub(state.current_battery) as u32
        } else {
            0
        };
    let off_used = state.screen_off_battery_used
        + if !state.is_screen_on && state.screen_off_start_battery > 0 {
            state.screen_off_start_battery.saturating_sub(state.current_battery) as u32
        } else {
            0
        };

    ScreenTimes {
        screen_on_ms: on_ms,
        screen_off_ms: off_ms,
        screen_on_battery_used: on_used,
        screen_off_battery_used: off_used,
        deep_sleep_ms: state.deep_sleep_ms,
        awake_ms: state.awake_ms,
    }
}

pub struct DrainRates {
    pub active_drain_pct_per_hr: f64,
    pub idle_drain_pct_per_hr: f64,
}

pub fn get_drain_rates() -> DrainRates {
    let times = get_screen_times();
    let on_h = times.screen_on_ms as f64 / 3_600_000.0;
    let off_h = times.screen_off_ms as f64 / 3_600_000.0;

    DrainRates {
        active_drain_pct_per_hr: if on_h > 0.01 { times.screen_on_battery_used as f64 / on_h } else { 0.0 },
        idle_drain_pct_per_hr: if off_h > 0.01 { times.screen_off_battery_used as f64 / off_h } else { 0.0 },
    }
}

fn current_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
