// app_monitor.rs — Detect foreground app via /proc scan.

use std::collections::VecDeque;
use std::sync::{OnceLock, RwLock};

#[derive(Debug, Clone, serde::Serialize)]
pub struct ForegroundApp {
    pub pid: i32,
    pub package: String,
}

struct MonitorState {
    recent: VecDeque<ForegroundApp>,
}

static STATE: OnceLock<RwLock<MonitorState>> = OnceLock::new();

const RING_SIZE: usize = 8;

fn state() -> &'static RwLock<MonitorState> {
    STATE.get().expect("app_monitor not initialized")
}

pub fn init() {
    let _ = STATE.set(RwLock::new(MonitorState {
        recent: VecDeque::with_capacity(RING_SIZE),
    }));
}

fn read_trimmed(path: &str) -> Option<String> {
    let s = std::fs::read_to_string(path).ok()?;
    let trimmed = s.trim().to_string();
    if trimmed.is_empty() { None } else { Some(trimmed) }
}

/// Scan /proc for numeric dirs, find the process with lowest oom_score_adj
/// in (0, 1000), return its package name from cmdline.
/// OPTIMIZATION: read /proc directly, skip slow path::exists() calls.
pub fn detect_fg() -> Option<ForegroundApp> {
    let proc_dir = std::fs::read_dir("/proc").ok()?;
    let mut best_pid: Option<(i32, i32)> = None; // (oom_score_adj, pid)

    for entry in proc_dir.flatten() {
        let name = entry.file_name();
        let name_str = name.to_string_lossy();
        let pid: i32 = match name_str.parse() {
            Ok(n) => n,
            Err(_) => continue,
        };

        let base = format!("/proc/{pid}");

        // Read oom_score_adj
        let oom = match read_trimmed(&format!("{base}/oom_score_adj")) {
            Some(s) => match s.parse::<i32>() {
                Ok(v) => v,
                Err(_) => continue,
            },
            None => continue,
        };

        if oom < 0 || oom >= 1000 {
            continue;
        }

        // Fast: skip kernel threads by checking cmdline (shorter than path::exists)
        let cmdline_path = format!("{base}/cmdline");
        let Ok(cmdline_bytes) = std::fs::read(&cmdline_path) else { continue };
        if cmdline_bytes.is_empty() { continue; }

        // Only Android app processes: cmdline must be a package-like name.
        // Android package names: no spaces, no slashes, no parens, ≥2 dots.
        let first_arg = cmdline_bytes
            .split(|&b| b == 0)
            .next()
            .unwrap_or_default();
        let first = String::from_utf8_lossy(first_arg);
        if first.contains(' ') || first.contains('/') || first.contains('(') {
            continue;
        }
        // Must have at least 2 dots (e.g. com.whatsapp) to be a package name
        let dot_count = first.bytes().filter(|b| *b == b'.').count();
        if dot_count < 2 {
            continue;
        }

        // Android top app = FOREGROUND_APP_ADJ (0); visible=100, perceptible=200.
        // Pick the process with the LOWEST adj; tie-break by highest PID (most
        // recently forked = most likely the actual foreground app).
        match &best_pid {
            Some((best_oom, best_pid)) if oom > *best_oom => {}
            Some((best_oom, best_pid)) if oom == *best_oom && pid < *best_pid => {}
            _ => best_pid = Some((oom, pid)),
        }
    }

    let (_, pid) = best_pid?;

    // Read cmdline — first null-delimited arg is the package name
    let cmdline = std::fs::read(format!("/proc/{pid}/cmdline")).ok()?;
    let parts: Vec<&[u8]> = cmdline.split(|&b| b == 0).collect();
    let package = parts
        .first()
        .map(|b| String::from_utf8_lossy(b).to_string())?;
    if package.is_empty() {
        return None;
    }

    let app = ForegroundApp { pid, package };

    let mut s = state().write().unwrap();
    if s.recent.len() >= RING_SIZE {
        s.recent.pop_front();
    }
    s.recent.push_back(app.clone());

    Some(app)
}

pub fn get_list() -> Vec<ForegroundApp> {
    state().read().unwrap().recent.iter().cloned().collect()
}
