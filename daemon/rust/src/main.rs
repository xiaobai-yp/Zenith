// main.rs — ZenithThermal daemon: full integration.
// stdin/stdout JSON protocol (newline-delimited), periodic
// sysfs/app/fps/benchmark monitoring. stderr for logging.

mod app_monitor;
mod benchmark;
mod battery_monitor;
mod fps_monitor;
mod profile_engine;
mod secure;
mod sysfs_monitor;
mod thermal_core;


/// Safe stderr logging — never panics even on broken pipe.
#[macro_export]
macro_rules! log {
    ($($arg:tt)*) => { let _ = writeln!(std::io::stderr(), $($arg)*); };
}
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::io::Write;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

const PROFILES_PATH: &str =
    "/data/data/com.zenith.thermal/files/profiles.json";

#[derive(Debug, Serialize, Deserialize)]
struct Request {
    cmd: String,
    #[serde(default)]
    args: serde_json::Value,
}

#[derive(Debug, Serialize)]
struct Response {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    data: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

impl Response {
    fn ok(data: serde_json::Value) -> Self {
        Self { ok: true, data: Some(data), error: None }
    }
    fn err(msg: &str) -> Self {
        Self { ok: false, data: None, error: Some(msg.to_string()) }
    }
}

// ── sysfs write helper ──

async fn write_sysfs(path: &str, val: &str) -> Result<(), String> {
    tokio::fs::write(path, val)
        .await
        .map_err(|e| format!("write {path}: {e}"))
}

// ── shared snapshot for command handlers ──

use std::sync::{OnceLock, RwLock};

static LAST_SNAPSHOT: OnceLock<RwLock<sysfs_monitor::SysfsSnapshot>> =
    OnceLock::new();

fn update_snapshot(snap: sysfs_monitor::SysfsSnapshot) {
    if let Some(lock) = LAST_SNAPSHOT.get() {
        *lock.write().unwrap() = snap;
    }
}

fn get_snapshot() -> sysfs_monitor::SysfsSnapshot {
    LAST_SNAPSHOT
        .get()
        .map(|l| l.read().unwrap().clone())
        .unwrap_or_default()
}

// ── command handler ──

async fn handle_cmd(req: Request) -> Response {
    match req.cmd.as_str() {
        "status" => {
            let snap = get_snapshot();
            let thermal = thermal_core::status();
            let (short_fps, long_fps) = fps_monitor::read();
            Response::ok(json!({
                "thermal": {
                    "active_profile": thermal.active_profile,
                    "cores": thermal.core_count,
                    "zones": thermal.zone_count,
                },
                "snapshot": snap,
                "battery": battery_monitor::get_stats(),
                "fps": {
                    "short": short_fps,
                    "long": long_fps,
                    "session": fps_monitor::get_avg(),
                },
            }))
        }

        "battery_notif" => {
            let snap = get_snapshot();
            let bat = &snap.battery;
            let stats = battery_monitor::get_stats();
            let times = battery_monitor::get_screen_times();

            // Read args from request
            let temp_unit = req.args.get("temp_unit")
                .and_then(|v| v.as_str()).unwrap_or("C");
            let show_power = req.args.get("show_power")
                .and_then(|v| v.as_bool()).unwrap_or(true);

            let charging = bat.online;

            // When charging: freeze to 0s, show Charging
            // When discharging: show real data
            let (current_ma, status) = if charging {
                (0.0f64, "Charging")
            } else {
                (bat.current_ua.abs() as f64 / 1000.0, "Discharging")
            };

            let (temp_val, temp_suffix) = match temp_unit {
                "F" => (bat.temp_centi as f64 / 10.0 * 9.0 / 5.0 + 32.0, "°F"),
                "K" => (bat.temp_centi as f64 / 10.0 + 273.15, "K"),
                _ => (bat.temp_centi as f64 / 10.0, "°C"),
            };

            let pct_of = |ms: u64| -> u64 {
                if times.total_ms > 0 { ms * 100 / times.total_ms } else { 0 }
            };
            let fmt_time = |ms: u64| -> String {
                let s = ms / 1000;
                let m = s / 60;
                let h = m / 60;
                if h > 0 { format!("{}h {}m", h, m % 60) }
                else if m > 0 { format!("{}m {}s", m, s % 60) }
                else { format!("{}s", s) }
            };

            // Power (W): only show when charging
            // mA: only show when discharging
            let power_w = bat.power_mw as f64 / 1000.0;
            let power_str = if charging && show_power && power_w > 0.05 {
                format!(" ({:.2}W)", power_w)
            } else {
                String::new()
            };
            let current_str = if charging {
                String::new()
            } else {
                format!(" {:.0} mA", current_ma)
            };

            // When charging: freeze drain rates and times to 0
            let (active_drain, idle_drain) = if charging {
                (0.0, 0.0)
            } else {
                (stats.screen_on_drain_pct_per_hr, stats.idle_drain_pct_per_hr)
            };
            let (s_on, s_off, ds, aw) = if charging {
                (0u64, 0u64, 0u64, 0u64)
            } else {
                (times.screen_on_ms, times.screen_off_ms, times.deep_sleep_ms, times.awake_ms)
            };

            let body = format!(
                "{}% {:.1}{} {}{}{}\n\
                 Active: {:.2}%/hr Idle: {:.2}%/hr\n\
                 Screen on: {} ({}%)\n\
                 Screen off: {} ({}%)\n\
                 Deep sleep: {} ({}%)\n\
                 Awake: {} ({}%)",
                bat.capacity, temp_val, temp_suffix, status, current_str, power_str,
                active_drain, idle_drain,
                fmt_time(s_on), pct_of(s_on),
                fmt_time(s_off), pct_of(s_off),
                fmt_time(ds), pct_of(ds),
                fmt_time(aw), pct_of(aw),
            );
            Response::ok(json!({ "body": body, "stats": stats }))
        }

        "reset_battery" => {
            battery_monitor::reset_times();
            Response::ok(json!({ "reset": true }))
        }

        "set" => {
            if let Some(obj) = req.args.as_object() {
                let mut applied = vec![];
                for (key, val) in obj {
                    if let Some(v) = val.as_str() {
                        let path = match key.as_str() {
                            "governor" => {
                                zen_path!("/sys/devices/system/cpu/cpufreq/policy0/scaling_governor")
                            }
                            "cpu_max" => {
                                zen_path!("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")
                            }
                            "gpu_max" => {
                                zen_path!("/sys/class/kgsl/kgsl-3d0/max_gpuclk")
                            }
                            _ => continue,
                        };
                        if write_sysfs(path, v).await.is_ok() {
                            applied.push(key.clone());
                        }
                    }
                }
                Response::ok(json!({ "applied": applied }))
            } else {
                Response::err("args must be an object")
            }
        }

        "apply_profile" => {
            let id = req
                .args
                .as_str()
                .or_else(|| {
                    req.args.get("id").and_then(|v| v.as_str())
                }).unwrap_or("");
            if id.is_empty() {
                return Response::err("missing profile id");
            }
            match thermal_core::apply_profile(id).await {
                Ok(()) => Response::ok(json!({ "applied": id })),
                Err(e) => Response::err(&e),
            }
        }

        "detect_fg" => {
            match app_monitor::detect_fg() {
                Some(app) => Response::ok(json!(app)),
                None => Response::ok(json!(null)),
            }
        }

        "list_profiles" => {
            let ids = profile_engine::list_profiles();
            let map = profile_engine::export_map();
            Response::ok(json!({ "profiles": ids, "package_map": map }))
        }

        "map_app" => {
            let pkg = req.args.get("package").and_then(|v| v.as_str());
            let pid = req.args.get("profile_id").and_then(|v| v.as_i64());
            match (pkg, pid) {
                (Some(p), Some(id)) => {
                    profile_engine::map_app(p, id as i32);
                    Response::ok(json!({ "mapped": p, "profile": id }))
                }
                _ => Response::err("need package and profile_id"),
            }
        }

        "reset_profiles" => {
            profile_engine::reset_map();
            Response::ok(json!({ "reset": true }))
        }

        "bench_start" => {
            benchmark::start();
            Response::ok(json!({ "started": true }))
        }

        "bench_stop" => {
            benchmark::stop();
            Response::ok(json!({ "stopped": true }))
        }

        "bench_data" => {
            let n = req.args.get("n").and_then(|v| v.as_u64()).unwrap_or(300) as usize;
            if req.args.get("full").and_then(|v| v.as_bool()).unwrap_or(false)
            {
                Response::ok(json!({ "data": benchmark::export_json() }))
            } else {
                let pts = benchmark::get_last_points(n);
                Response::ok(json!({ "data": pts }))
            }
        }

        "read_sysfs" => {
            let path = req
                .args
                .as_str()
                .or_else(|| {
                    req.args.get("path").and_then(|v| v.as_str())
                });
            match path {
                Some(p) => match tokio::fs::read_to_string(p).await {
                    Ok(v) => {
                        Response::ok(json!({ "value": v.trim() }))
                    }
                    Err(e) => Response::err(&format!("read: {e}")),
                },
                None => Response::err("missing path"),
            }
        }

        "read_app_list" => {
            Response::ok(json!(app_monitor::get_list()))
        }

        "reset_fps_session" => {
            fps_monitor::reset_session();
            Response::ok(json!({ "reset": true }))
        }

        "fps_avg" => {
            let (avg, min, max) = fps_monitor::get_avg();
            Response::ok(json!({ "avg": avg, "min": min, "max": max }))
        }

        "battery_stats" => {
            Response::ok(json!(battery_monitor::get_stats()))
        }

        _ => Response::err(&format!("unknown cmd: {}", req.cmd)),
    }
}

// ── main ──

#[tokio::main]
async fn main() {
    // Custom panic hook: exit cleanly instead of SIGABRT on broken stderr pipe.
    std::panic::set_hook(Box::new(|_| std::process::exit(1)));
    let _ = writeln!(std::io::stderr(), "[zenithd] starting daemon");

    // Init all modules
    thermal_core::init()
        .await
        .unwrap_or_else(|e| { let _ = writeln!(std::io::stderr(), "[zenithd] thermal_core init: {e}"); });

    let _ = profile_engine::init(PROFILES_PATH).await;

    app_monitor::init();
    sysfs_monitor::init().await;
    battery_monitor::init();
    fps_monitor::init();
    benchmark::init();

    let _ = LAST_SNAPSHOT.set(RwLock::new(sysfs_monitor::SysfsSnapshot::default()));

    // Periodic monitoring tasks (no shutdown channel needed — exits when
    // the tokio runtime drops, which happens when main returns).
    // ── Monitoring thread ──
    // Runs on a dedicated OS thread with std::thread::sleep.
    // Uses Handle::block_on() to call async sysfs/fps functions.
    // Never competes with tokio stdin I/O.
    let monitor_handle = std::thread::Builder::new()
        .name("zenith-monitor".into())
        .spawn(move || {
            // Create a dedicated single-threaded tokio runtime for this OS thread.
            // Handle::current() would panic here since we're not in a tokio context.
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("create monitor runtime");

            // Read initial property
            let mut last_prop = String::new();
            if let Ok(v) = rt.block_on(read_prop("persist.sys.zenith.thermal")) {
                last_prop = v;
                if !last_prop.is_empty() {
                    let _ = rt.block_on(thermal_core::apply_profile(&last_prop));
                }
            }

            loop {
                // sysfs snapshot (every 5s)
                let snap = rt.block_on(sysfs_monitor::read());
                battery_monitor::update(
                    snap.battery.capacity,
                    snap.battery.current_ua,
                    snap.battery.voltage_uv,
                    snap.battery.online,
                    now_ms(),
                    snap.screen_on,
                );
                battery_monitor::update_screen_times(now_ms(), snap.screen_on, snap.battery.current_ua);
                update_snapshot(snap);

                // Save session every 30s for persistence across restarts
                if crate::now_ms() % 30_000 < 10_000 {
                    battery_monitor::save_session();
                }

                // fps + benchmark (5 iterations x 1s = 5s total)
                for _ in 0..5 {
                    std::thread::sleep(std::time::Duration::from_secs(1));
                    let (short, _long) = fps_monitor::read();
                    if benchmark::is_active() {
                        let snap = get_snapshot();
                        benchmark::record(&snap, short);
                    }
                }

                // foreground app detection + perapp profile apply
                if let Some(fg) = app_monitor::detect_fg() {
                    let profile_id = profile_engine::lookup(&fg.package);
                    let active = thermal_core::get_active_profile().unwrap_or_default();
                    if profile_id != 0 && active != profile_id.to_string() {
                        let _ = rt.block_on(thermal_core::apply_profile(&profile_id.to_string()));
                    }
                }

                // property poll
                if let Ok(v) = rt.block_on(read_prop("persist.sys.zenith.thermal")) {
                    if !v.is_empty() && v != last_prop {
                        let _ = writeln!(std::io::stderr(), "[zenithd] property change: {last_prop} -> {v}");
                        last_prop = v;
                        let _ = rt.block_on(thermal_core::apply_profile(&last_prop));
                    }
                }
            }
        })
        .expect("spawn monitor thread");

    // Stdin/stdout command loop.
    // stdin carries newline-delimited JSON requests from the Java Process.
    // stdout carries newline-delimited JSON responses back.
    // When the Java side destroys the Process, the pipe closes, stdin
    // reaches EOF, and the loop breaks — triggering clean shutdown.
    let _ = writeln!(std::io::stderr(), "[zenithd] ready - reading commands from stdin");

    let stdin = tokio::io::stdin();
    let mut stdout = tokio::io::stdout();
    let mut lines = BufReader::new(stdin).lines();

    while let Ok(Some(line)) = lines.next_line().await {
        if line.is_empty() {
            continue;
        }
        let resp = match serde_json::from_str::<Request>(&line) {
            Ok(req) => handle_cmd(req).await,
            Err(e) => Response::err(&format!("parse: {e}")),
        };
        let mut out = serde_json::to_string(&resp).unwrap_or_default();
        out.push('\n');
        if stdout.write_all(out.as_bytes()).await.is_err() {
            break; // stdout broken — pipe closed
        }
        let _ = stdout.flush().await;
    }

    drop(monitor_handle); // thread dies with process
    let _ = writeln!(std::io::stderr(), "[zenithd] stdin EOF - shutting down");
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// Read a system property via `getprop`.
async fn read_prop(name: &str) -> Result<String, String> {
    let out = tokio::process::Command::new("getprop")
        .arg(name)
        .output()
        .await
        .map_err(|e| format!("getprop: {e}"))?;
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
}
