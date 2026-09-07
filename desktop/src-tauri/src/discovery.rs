use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::Serialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, State};

// ---------------------------------------------------------------------------
// Service type SSOT — matches Android NsdAdvertiser.SERVICE_TYPE
// ---------------------------------------------------------------------------

pub const BROWSE_TYPE: &str = "_c2vsync._tcp.local.";

// ---------------------------------------------------------------------------
// Instance name helper (no split('.')[0] — names can contain dots)
// Strips known suffix to get the human-readable instance name.
// e.g. "My.Phone._c2vsync._tcp.local." → "My.Phone"
// ---------------------------------------------------------------------------

fn instance_name(fullname: &str) -> String {
    for suffix in ["._c2vsync._tcp.local.", "._c2vsync._tcp."] {
        if let Some(prefix) = fullname.strip_suffix(suffix) {
            return prefix.to_string();
        }
    }
    fullname.to_string()
}

// ---------------------------------------------------------------------------
// State — BrowserHandle owns a daemon clone for shutdown.
// Drop calls stop_browse + shutdown which disconnects the browse receiver
// and causes the worker thread to exit naturally (blocking recv returns Err).
// ---------------------------------------------------------------------------

struct BrowserHandle {
    daemon: ServiceDaemon,
}

impl Drop for BrowserHandle {
    fn drop(&mut self) {
        let _ = self.daemon.stop_browse(BROWSE_TYPE);
        let _ = self.daemon.shutdown();
    }
}

// G6: BrowserState holds both the browser lifecycle handle and an in-memory
// snapshot so list_devices() can return current devices without a race.
pub struct BrowserState {
    handle: Mutex<Option<BrowserHandle>>,
    snapshot: Arc<Mutex<HashMap<String, DeviceFoundPayload>>>,
}

impl BrowserState {
    pub fn new() -> Self {
        BrowserState {
            handle: Mutex::new(None),
            snapshot: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn stop_all(&self) {
        if let Ok(mut guard) = self.handle.lock() {
            guard.take(); // Drop triggers BrowserHandle::drop → daemon shutdown
        }
        if let Ok(mut snap) = self.snapshot.lock() {
            snap.clear();
        }
    }
}

// ---------------------------------------------------------------------------
// Event payloads
// ---------------------------------------------------------------------------

#[derive(Serialize, Clone)]
pub struct DeviceFoundPayload {
    pub name: String,    // fullname — used as map key in JS
    pub display: String, // human-readable instance name
    pub ip: String,
    pub port: u16,
}

#[derive(Serialize, Clone)]
struct DeviceLostPayload {
    name: String, // fullname — must match DeviceFoundPayload.name for JS map consistency
}

// ---------------------------------------------------------------------------
// start_browser — creates ServiceDaemon on the calling thread.
// Returns None (with browser-error event) if init or browse fails.
// No orphan Some is stored on failure.
//
// G1: Emits stable error codes ("init_failed" / "browse_failed") — never
//     format!() with Display so raw OS strings never reach the UI.
// G4: Worker only needs `receiver` + `app_handle`; no daemon clone in thread.
//     BrowserHandle owns the daemon for Drop shutdown; the Receiver is
//     independently owned and stays valid until daemon shuts down.
// ---------------------------------------------------------------------------

fn start_browser(
    app_handle: AppHandle,
    snapshot: Arc<Mutex<HashMap<String, DeviceFoundPayload>>>,
) -> Option<BrowserHandle> {
    let daemon = match ServiceDaemon::new() {
        Ok(d) => d,
        Err(_) => {
            let _ = app_handle.emit("browser-error", "init_failed");
            return None;
        }
    };

    let receiver = match daemon.browse(BROWSE_TYPE) {
        Ok(r) => r,
        Err(_) => {
            let _ = app_handle.emit("browser-error", "browse_failed");
            let _ = daemon.shutdown();
            return None;
        }
    };

    std::thread::spawn(move || {
        // Blocking recv — no CPU polling. Exits when daemon shuts down
        // (BrowserHandle Drop → stop_browse + shutdown → channel closes → Err here).
        loop {
            match receiver.recv() {
                Ok(ServiceEvent::ServiceResolved(info)) => {
                    let fullname = info.get_fullname().to_string();
                    let display = instance_name(&fullname);
                    // Prefer first IPv4; no full IP logged per policy
                    let ip = info
                        .get_addresses()
                        .iter()
                        .find(|a| a.is_ipv4())
                        .or_else(|| info.get_addresses().iter().next())
                        .map(|a| a.to_string())
                        .unwrap_or_default();
                    let port = info.get_port();
                    let payload = DeviceFoundPayload {
                        name: fullname.clone(),
                        display,
                        ip,
                        port,
                    };
                    // G6: upsert into snapshot before emitting
                    if let Ok(mut snap) = snapshot.lock() {
                        snap.insert(fullname, payload.clone());
                    }
                    let _ = app_handle.emit("device-found", payload);
                }
                Ok(ServiceEvent::ServiceRemoved(_, fullname)) => {
                    if let Ok(mut snap) = snapshot.lock() {
                        snap.remove(&fullname);
                    }
                    let _ = app_handle.emit(
                        "device-lost",
                        DeviceLostPayload { name: fullname },
                    );
                }
                Ok(_) => {} // SearchStarted / ServiceFound / SearchStopped — ignore
                Err(_) => break, // RecvError::Disconnected — daemon shut down cleanly
            }
        }
    });

    Some(BrowserHandle { daemon })
}

// ---------------------------------------------------------------------------
// Auto-start — called from Builder setup; fail-open (no crash on error)
// ---------------------------------------------------------------------------

pub fn auto_start(app_handle: AppHandle, state: &BrowserState) {
    if let Ok(mut guard) = state.handle.lock() {
        if guard.is_none() {
            *guard = start_browser(app_handle, Arc::clone(&state.snapshot));
            // None on failure → no orphan Some stored
        }
    }
    // If lock is poisoned, browsing simply won't start — no crash
}

// ---------------------------------------------------------------------------
// Tauri commands (idempotent start; stop tears down cleanly)
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn start_discovery(
    state: State<'_, BrowserState>,
    app_handle: AppHandle,
) -> Result<(), String> {
    let mut guard = state.handle.lock().map_err(|_| "State lock poisoned")?;
    if guard.is_none() {
        *guard = start_browser(app_handle, Arc::clone(&state.snapshot));
    }
    Ok(())
}

#[tauri::command]
pub fn stop_discovery(state: State<'_, BrowserState>) -> Result<(), String> {
    let mut guard = state.handle.lock().map_err(|_| "State lock poisoned")?;
    guard.take(); // Drop triggers BrowserHandle::drop → stop_browse + shutdown
    Ok(())
}

// G6: Snapshot query — returns current device list without waiting for events.
// JS calls this after registering all listeners to avoid the setup() race.
#[tauri::command]
pub fn list_devices(state: State<'_, BrowserState>) -> Vec<DeviceFoundPayload> {
    state
        .snapshot
        .lock()
        .map(|s| s.values().cloned().collect())
        .unwrap_or_default()
}
