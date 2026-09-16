// bench_store.rs — Persistent session storage in /data/zenith/sessions/
// On bench_stop, flushes ring buffer to JSON file. App queries via IPC.
use crate::benchmark::BenchPoint;
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Write;
use std::path::PathBuf;

const SESSIONS_DIR: &str = "/data/zenith/sessions";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionMeta {
    pub id: String,             // "nagramx_2026-09-16_19-21-06"
    pub package: String,        // "fork.risin42.nagramx"
    pub started_at: u64,        // epoch ms
    pub stopped_at: u64,        // epoch ms
    pub duration_ms: u64,
    pub point_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionData {
    pub meta: SessionMeta,
    pub points: Vec<BenchPoint>,
}

fn sessions_dir() -> PathBuf {
    PathBuf::from(SESSIONS_DIR)
}

/// Ensure sessions directory exists (called on init).
pub fn init() {
    let _ = fs::create_dir_all(sessions_dir());
}

/// Save a completed session to disk.
pub fn save_session(
    package: &str,
    started_at: u64,
    points: &[BenchPoint],
) -> Result<String, String> {
    let stopped_at = points.last().map(|p| p.timestamp_ms).unwrap_or(started_at);
    let duration_ms = stopped_at.saturating_sub(started_at);

    // ID: last_segment_of_package + timestamp
    let short = package.split('.').last().unwrap_or("unknown");
    let ts = chrono_timestamp(stopped_at);
    let id = format!("{}_{}", short, ts);

    let meta = SessionMeta {
        id: id.clone(),
        package: package.to_string(),
        started_at,
        stopped_at,
        duration_ms,
        point_count: points.len(),
    };

    let data = SessionData {
        meta,
        points: points.to_vec(),
    };

    let json = serde_json::to_string(&data).map_err(|e| format!("serialize: {e}"))?;
    let path = sessions_dir().join(format!("{id}.json"));
    fs::write(&path, json).map_err(|e| format!("write: {e}"))?;

    crate::log!("[bench_store] saved session {} ({} pts, {}ms)", id, points.len(), duration_ms);
    Ok(id)
}

/// List all saved sessions (metadata only, no points).
pub fn list_sessions() -> Vec<SessionMeta> {
    let dir = sessions_dir();
    if !dir.exists() {
        return vec![];
    }
    let mut sessions = vec![];
    if let Ok(entries) = fs::read_dir(&dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) == Some("json") {
                if let Ok(content) = fs::read_to_string(&path) {
                    if let Ok(data) = serde_json::from_str::<SessionData>(&content) {
                        sessions.push(data.meta);
                    }
                }
            }
        }
    }
    sessions.sort_by(|a, b| b.started_at.cmp(&a.started_at));
    sessions
}

/// Get full session data (meta + points) by ID.
pub fn get_session(id: &str) -> Option<SessionData> {
    let path = sessions_dir().join(format!("{id}.json"));
    let content = fs::read_to_string(path).ok()?;
    serde_json::from_str(&content).ok()
}

/// Delete a session by ID.
pub fn delete_session(id: &str) -> bool {
    let path = sessions_dir().join(format!("{id}.json"));
    fs::remove_file(path).is_ok()
}

/// Delete all sessions.
pub fn delete_all() -> usize {
    let dir = sessions_dir();
    if !dir.exists() {
        return 0;
    }
    let mut count = 0;
    if let Ok(entries) = fs::read_dir(&dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) == Some("json") {
                if fs::remove_file(&path).is_ok() {
                    count += 1;
                }
            }
        }
    }
    count
}

/// Convert epoch ms to "yyyy-MM-dd_HH-mm-ss" (no allocation-heavy chrono).
fn chrono_timestamp(ms: u64) -> String {
    let secs = (ms / 1000) as u64;
    // Simple UTC conversion (good enough for filenames)
    let days = secs / 86400;
    let time_of_day = secs % 86400;
    let h = time_of_day / 3600;
    let m = (time_of_day % 3600) / 60;
    let s = time_of_day % 60;

    // Approximate date from days since epoch (1970-01-01)
    // Use a simplified calendar calculation
    let mut y = 1970u64;
    let mut remaining = days;
    loop {
        let days_in_year = if is_leap(y) { 366 } else { 365 };
        if remaining < days_in_year {
            break;
        }
        remaining -= days_in_year;
        y += 1;
    }
    let leap = is_leap(y);
    let month_days: [u64; 12] = [
        31, if leap { 29 } else { 28 }, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31,
    ];
    let mut m_idx = 0;
    for (i, &md) in month_days.iter().enumerate() {
        if remaining < md {
            m_idx = i;
            break;
        }
        remaining -= md;
    }
    let day = remaining + 1;
    let month = m_idx as u64 + 1;

    format!("{y:04}-{month:02}-{day:02}_{h:02}-{m:02}-{s:02}")
}

fn is_leap(y: u64) -> bool {
    (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}
