// main.rs — ZenithThermal daemon: full integration.
// Unix socket JSON protocol, periodic sysfs/app/fps/benchmark monitoring.

mod app_monitor;
mod benchmark;
mod battery_monitor;
mod fps_monitor;
mod profile_engine;
mod secure;
mod sysfs_monitor;
mod thermal_core;

use serde::{Deserialize, Serialize};
use serde_json::json;
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixListener;
use tokio::sync::watch;


const SOCKET_PATH: &str =
    "/data/data/com.zenith.thermal/files/zenithd.sock";
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
                });
            match id {
                Some(id_str) => {
                    match thermal_core::apply_profile(id_str).await {
                        Ok(()) => {
                            Response::ok(json!({ "applied": id_str }))
                        }
                        Err(e) => Response::err(&e),
                    }
                }
                None => Response::err("missing profile id"),
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
    eprintln!("[zenithd] starting daemon");

    // Accept app uid as first arg (e.g. `zenithd 10260`). Daemon chowns the
    // socket to this uid so the app can connect.
    let app_uid: Option<u32> = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok());
    if let Some(uid) = app_uid {
        eprintln!("[zenithd] will chown socket to uid {uid}");
    }

    // Init all modules
    thermal_core::init()
        .await
        .unwrap_or_else(|e| eprintln!("[zenithd] thermal_core init: {e}"));

    let _ = profile_engine::init(PROFILES_PATH).await;

    app_monitor::init();
    sysfs_monitor::init().await;
    battery_monitor::init();
    fps_monitor::init();
    benchmark::init();

    let _ = LAST_SNAPSHOT.set(RwLock::new(sysfs_monitor::SysfsSnapshot::default()));

    // Ensure socket dir exists
    let socket_path = zen_path!(SOCKET_PATH);
    let _ = tokio::fs::remove_file(socket_path).await;
    if let Some(parent) = PathBuf::from(socket_path).parent() {
        let _ = tokio::fs::create_dir_all(parent).await;
    }

    let listener = UnixListener::bind(socket_path).expect("bind socket");
    // Make the socket accessible to the app's uid (root-owned socket in the
    // app dir is blocked by SELinux for app-domain processes).
    if let Some(uid) = app_uid {
        let _ = std::process::Command::new("/system/bin/chown")
            .args([&format!("{uid}:{uid}"), socket_path])
            .status();
        // Socket needs write perm for connect() — chown alone leaves mode 755
        // (unix socket connect requires rw; owner-only write is not enough
        // because UnixListener::bind creates with 755).
        let _ = std::process::Command::new("/system/bin/chmod")
            .args(["660", socket_path])
            .status();
    }
    eprintln!("[zenithd] listening on {socket_path}");

    // Shutdown signal
    let (shutdown_tx, mut shutdown_rx) = watch::channel(false);
    let mut monitor_rx = shutdown_rx.clone();

    // Periodic monitoring tasks
    let monitor_handle = tokio::spawn(async move {
        let mut interval_sysfs =
            tokio::time::interval(std::time::Duration::from_secs(5));
        let mut interval_app =
            tokio::time::interval(std::time::Duration::from_secs(2));
        let mut interval_fps =
            tokio::time::interval(std::time::Duration::from_secs(1));
        let mut interval_prop =
            tokio::time::interval(std::time::Duration::from_secs(3));

        // Track last applied property value (persist.sys.zenith.thermal)
        let mut last_prop = String::new();
        if let Ok(v) = read_prop("persist.sys.zenith.thermal").await {
            last_prop = v;
            if !last_prop.is_empty() {
                let _ = thermal_core::apply_profile(&last_prop).await;
            }
        }

        loop {
            tokio::select! {
                _ = monitor_rx.changed() => break,
                _ = interval_sysfs.tick() => {
                    let snap = sysfs_monitor::read().await;
                    battery_monitor::update(
                        snap.battery.capacity,
                        snap.battery.current_ua,
                        snap.battery.voltage_uv,
                        snap.battery.online,
                        now_ms(),
                    );
                    update_snapshot(snap);
                }
                _ = interval_fps.tick() => {
                    let (short, _long) = fps_monitor::read();
                    if benchmark::is_active() {
                        let snap = get_snapshot();
                        benchmark::record(&snap, short);
                    }
                }
                _ = interval_app.tick() => {
                    let _ = app_monitor::detect_fg();
                }
                _ = interval_prop.tick() => {
                    if let Ok(v) = read_prop("persist.sys.zenith.thermal").await {
                        if !v.is_empty() && v != last_prop {
                            eprintln!("[zenithd] property change: {last_prop} -> {v}");
                            let _ = thermal_core::apply_profile(&v).await;
                            last_prop = v;
                        }
                    }
                }
            }
        }
    });

    // Socket accept loop
    loop {
        tokio::select! {
            _ = shutdown_rx.changed() => break,
            result = listener.accept() => {
                match result {
                    Ok((mut stream, _)) => {
                        tokio::spawn(async move {
                            let (reader, mut writer) = stream.split();
                            let mut lines =
                                BufReader::new(reader).lines();

                            while let Ok(Some(line)) =
                                lines.next_line().await
                            {
                                if line.is_empty() {
                                    continue;
                                }
                                let resp = match serde_json::from_str::<
                                    Request,
                                >(&line) {
                                    Ok(req) => handle_cmd(req).await,
                                    Err(e) => Response::err(
                                        &format!("parse: {e}"),
                                    ),
                                };
                                let mut out =
                                    serde_json::to_string(&resp)
                                        .unwrap_or_default();
                                out.push('\n');
                                let _ =
                                    writer.write_all(out.as_bytes()).await;
                            }
                            // Client disconnected — do NOT trigger
                            // daemon shutdown on disconnect (C code
                            // only shuts down on signal).
                        });
                    }
                    Err(e) => {
                        eprintln!("[zenithd] accept error: {e}");
                    }
                }
            }
        }
    }

    monitor_handle.abort();

    // Clean up socket file (matches C socket_server_stop behavior)
    let _ = tokio::fs::remove_file(socket_path).await;

    eprintln!("[zenithd] shutdown");
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
