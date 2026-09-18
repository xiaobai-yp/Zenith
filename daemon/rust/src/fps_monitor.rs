// fps_monitor.rs — FPS from sysfs with dual EMA and session stats.
use std::io::Write;

use std::sync::{OnceLock, RwLock};

const EMA_SHORT_ALPHA: f64 = 2.0 / 31.0; // ~30-sample window (matches C: 2/(N+1))
const EMA_LONG_ALPHA: f64 = 2.0 / 181.0; // ~180-sample window (matches C: 2/(N+1))

#[derive(Debug, Clone)]
pub struct FpsMonitor {
    active_path: Option<String>,
    ema_short: f64,
    ema_long: f64,
    initialized_ema: bool,
    // session stats
    session_sum: i64,
    session_count: i64,
    session_min: i32,
    session_max: i32,
    // frame time (ms) — max observed this session
    max_frame_time_ms: u64,
}

static STATE: OnceLock<RwLock<FpsMonitor>> = OnceLock::new();

const FPS_PATHS: &[&str] = &[
    "/sys/class/drm/sde-crtc-0/measured_fps",
    "/sys/class/graphics/fb0/fps",
    "/sys/class/misc/mali0/device/fps",
    "/sys/kernel/debug/mali/fps",
    "/sys/class/drm/card0/sde-crtc-0/measured_fps",
];

fn state() -> &'static RwLock<FpsMonitor> {
    STATE.get().expect("fps_monitor not initialized")
}

pub fn init() {
    use std::path::Path;
    let active = FPS_PATHS
        .iter()
        .find(|p| Path::new(p).exists())
        .map(|s| s.to_string());

    if let Some(ref p) = active {
        crate::log!("[fps_monitor] using path: {p}");
    } else {
        crate::log!("[fps_monitor] no sysfs FPS path found, fallback=60fps");
    }

    let _ = STATE.set(RwLock::new(FpsMonitor {
        active_path: active,
        ema_short: 60.0,
        ema_long: 60.0,
        initialized_ema: false,
        session_sum: 0,
        session_count: 0,
        session_min: i32::MAX,
        session_max: i32::MIN,
        max_frame_time_ms: 0,
    }));
}

pub fn read() -> (i32, i32) {
    let mut s = state().write().unwrap();

    let raw_fps: f64 = if let Some(ref path) = s.active_path {
        let content = std::fs::read_to_string(path).ok().unwrap_or_default();
        // Parse "fps: 37.9 duration:500000 frame_count:19"
        let mut ft_ms = 0u64;
        if let Some(dur_pos) = content.find("duration:") {
            let rest = &content[dur_pos + 9..];
            if let Some(end) = rest.find(|c: char| !c.is_ascii_digit()) {
                if let Ok(nanos) = rest[..end].parse::<u64>() {
                    ft_ms = nanos / 1_000_000;
                    if ft_ms > s.max_frame_time_ms {
                        s.max_frame_time_ms = ft_ms;
                    }
                }
            }
        }
        // Parse fps from "fps: 37.9"
        if let Some(fps_pos) = content.find("fps:") {
            let rest = &content[fps_pos + 4..];
            if let Ok(f) = rest.trim().split_whitespace().next().unwrap_or("").parse() {
                f
            } else { 60.0 }
        } else {
            content.trim().parse().unwrap_or(60.0)
        }
    } else {
        60.0
    };

    if !s.initialized_ema {
        s.ema_short = raw_fps;
        s.ema_long = raw_fps;
        s.initialized_ema = true;
    } else {
        s.ema_short = EMA_SHORT_ALPHA * raw_fps + (1.0 - EMA_SHORT_ALPHA) * s.ema_short;
        s.ema_long = EMA_LONG_ALPHA * raw_fps + (1.0 - EMA_LONG_ALPHA) * s.ema_long;
    }

    let short_fps = s.ema_short.round() as i32;
    let long_fps = s.ema_long.round() as i32;

    // session stats
    s.session_sum += short_fps as i64;
    s.session_count += 1;
    if short_fps < s.session_min {
        s.session_min = short_fps;
    }
    if short_fps > s.session_max {
        s.session_max = short_fps;
    }

    (short_fps, long_fps)
}

/// Returns (avg, min, max) for the current session.
pub fn get_avg() -> (i32, i32, i32) {
    let s = state().read().unwrap();
    if s.session_count == 0 {
        return (0, 0, 0);
    }
    let avg = (s.session_sum / s.session_count) as i32;
    let min = if s.session_min == i32::MAX { 0 } else { s.session_min };
    let max = if s.session_max == i32::MIN { 0 } else { s.session_max };
    (avg, min, max)
}

pub fn reset_session() {
    let mut s = state().write().unwrap();
    s.session_sum = 0;
    s.session_count = 0;
    s.session_min = i32::MAX;
    s.session_max = i32::MIN;
    s.max_frame_time_ms = 0;
    // Reset EMA state (matches C fps_monitor_reset_session)
    s.ema_short = 60.0;
    s.ema_long = 60.0;
    s.initialized_ema = false;
}

/// Max single-frame render time observed this session (ms).
pub fn max_frame_time_ms() -> u64 {
    state().read().unwrap().max_frame_time_ms
}

/// Read jank stats from gfxinfo (returns (fps, jank, big_jank, max_frame_time_ms))
pub fn read_jank() -> (i32, i32, i32, i32) {
    // Try to get frame stats from gfxinfo for the current foreground app
    // This is a simplified version - in production you'd parse dumpsys gfxinfo output
    // For now return placeholder values
    let max_ft = max_frame_time_ms() as i32;
    (0, 0, 0, max_ft)
}
