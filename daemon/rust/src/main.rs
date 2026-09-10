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
                "battery": battery_monitor::get_drain_rates(),
                "fps": {
                    "short": short_fps,
                    "long": long_fps,
                    "session": fps_monitor::get_avg(),
                },
            }))
        }

        "battery_notif" => {
            let times = battery_monitor::get_screen_times();
            let drain = battery_monitor::get_drain_rates();
            let bat = battery_monitor::readings();

            // Read args from request
            let temp_unit = req.args.get("temp_unit")
                .and_then(|v| v.as_str()).unwrap_or("C");
            let show_power = req.args.get("show_power")
                .and_then(|v| v.as_bool()).unwrap_or(true);

            let charging = bat.charging;

            // Charging status label based on power wattage.
            let power_w = bat.power_mw as f64 / 1000.0;
            let current_ma = bat.current_ma as f64;
            let status = if charging {
                // status=="Full" is the kernel's full-charge signal
                if bat.status == "Full" {
                    "Fully charged"
                } else if power_w >= 7.0 {
                    "Charging rapidly ⚡"
                } else if power_w >= 2.0 {
                    "Charging ⚡"
                } else {
                    "Charging slowly ⚡"
                }
            } else {
                "Discharging"
            };

            let (temp_val, temp_suffix) = match temp_unit {
                "F" => (bat.temp_centi as f64 / 10.0 * 9.0 / 5.0 + 32.0, "°F"),
                "K" => (bat.temp_centi as f64 / 10.0 + 273.15, "K"),
                _ => (bat.temp_centi as f64 / 10.0, "°C"),
            };

            let fmt_time = |ms: u64| -> String {
                let s = ms / 1000;
                let m = s / 60;
                let h = m / 60;
                if h > 0 { format!("{}h {}m {}s", h, m % 60, s % 60) }
                else if m > 0 { format!("{}m {}s", m, s % 60) }
                else { format!("{}s", s) }
            };

            // Power string: only when charging (not Full Charge)
            let is_full_charge = bat.status == "Full";
            let power_str = if charging && !is_full_charge && show_power && power_w > 0.05 {
                format!(" • {:.1}W", power_w)
            } else {
                String::new()
            };

            // Current: always show except Full Charge
            let current_str = if is_full_charge {
                String::new()
            } else {
                format!(" {:.0} mA", current_ma)
            };

            // Deep sleep / awake % = time residency (always show, even while charging)
            let ds_total = times.deep_sleep_ms + times.awake_ms;
            let ds_pct = if ds_total > 0 { times.deep_sleep_ms as f64 / ds_total as f64 * 100.0 } else { 0.0 };
            let aw_pct = if ds_total > 0 { times.awake_ms as f64 / ds_total as f64 * 100.0 } else { 0.0 };

            let (active_drain, idle_drain) = if charging {
                (0.0, 0.0)
            } else {
                (drain.active_drain_pct_per_hr, drain.idle_drain_pct_per_hr)
            };

            // Instantaneous rate from live current sensor (like Thermal Services):
            // current_ma / capacity_mah * 3600 = %/hr. EMA-smoothed in daemon.
            let (inst_active, inst_idle) = if charging {
                (0.0, 0.0)
            } else {
                battery_monitor::get_inst_rates()
            };
            // Active/idle screen times freeze during charging; deep sleep/awake always live.
            let (s_on, s_off, on_pct, off_pct) = if charging {
                (0u64, 0u64, 0u32, 0u32)
            } else {
                (times.screen_on_ms, times.screen_off_ms, times.screen_on_pct, times.screen_off_pct)
            };

            let body = format!(
                "{}% • {:.1}{} • {}{}{}\n\
                 Active: {:.2}%/hr ({:.1}%/hr now) Idle: {:.2}%/hr ({:.1}%/hr now)\n\
                 Screen on: {} ({}%)\n\
                 Screen off: {} ({}%)\n\
                 Deep sleep: {} ({:.1}%)\n\
                 Awake: {} ({:.1}%)",
                bat.capacity, temp_val, temp_suffix, status, current_str, power_str,
                active_drain, inst_active, idle_drain, inst_idle,
                fmt_time(s_on), on_pct,
                fmt_time(s_off), off_pct,
                fmt_time(times.deep_sleep_ms), ds_pct,
                fmt_time(times.awake_ms), aw_pct,
            );
            Response::ok(json!({ "body": body, "drain": drain }))
        }

        "reset_battery" => {
            battery_monitor::reset();
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
            Response::ok(json!({
                "drain": battery_monitor::get_drain_rates(),
                "times": battery_monitor::get_screen_times(),
                "readings": battery_monitor::readings(),
            }))
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
    battery_monitor::init().await;
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

            let mut tick = 0u64;
            loop {
                // Screen state from brightness
                let screen_on = {
                    let snap = get_snapshot();
                    snap.screen_on
                };

                // Battery state update every 1s (new API: async)
                rt.block_on(battery_monitor::update(screen_on));

                // Full sysfs snapshot every 5 ticks
                tick += 1;
                if tick % 5 == 0 {
                    let snap = rt.block_on(sysfs_monitor::read());
                    update_snapshot(snap);
                }

                // fps + benchmark (1s tick)
                {
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

                std::thread::sleep(std::time::Duration::from_secs(1));
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
