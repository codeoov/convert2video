use keyring::Entry;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::{Emitter, State};

// ---------------------------------------------------------------------------
// Named constants — SSOT; no inline string literals in logic
// ---------------------------------------------------------------------------

/// SSOT with Android PairingProtocol; unused in Phase 7.
#[allow(dead_code)]
pub const PING_ROUTE: &str = "/ping";
const PAIR_REQUEST_ROUTE: &str = "/pair/request";
pub const WHO_AM_I_ROUTE: &str = "/whoami";
pub const AUTH_HEADER_NAME: &str = "Authorization";
pub const AUTH_HEADER_PREFIX: &str = "Bearer ";
const PAIRING_WAIT_SECS: u64 = 70;
pub const KEYRING_SERVICE: &str = "c2v-desktop";
pub const KEYRING_USER: &str = "pairing-token";

// JSON body keys
const DEVICE_NAME_KEY: &str = "deviceName";
const REQUESTED_AT_KEY: &str = "requestedAtEpochMillis";
const TOKEN_KEY: &str = "token";
const ERROR_KEY: &str = "error";

// Pairing error codes (same strings as Android; pub so upload.rs can share)
#[allow(dead_code)]
const PAIRING_ERROR_DENIED: &str = "denied";
pub const PAIRING_ERROR_TIMEOUT: &str = "timeout";
const PAIRING_ERROR_IN_PROGRESS: &str = "pairing_in_progress";
#[allow(dead_code)]
const PAIRING_ERROR_BACKGROUNDED: &str = "app_backgrounded";
#[allow(dead_code)]
const PAIRING_ERROR_SERVICE_STOPPED: &str = "service_stopped";
pub const ERROR_BAD_REQUEST: &str = "bad_request";
pub const ERROR_UNAUTHORIZED: &str = "unauthorized";
pub const ERROR_INTERNAL: &str = "internal";

// Phase string values (pub so upload.rs can import)
pub const PHASE_IDLE: &str = "idle";
pub const PHASE_WAITING: &str = "waiting";
pub const PHASE_PAIRED: &str = "paired";
pub const PHASE_UPLOADING: &str = "uploading";
pub const PHASE_SUCCESS: &str = "success";
pub const PHASE_FAILED: &str = "failed";

// ---------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------

pub struct PairingInner {
    pub phase: String,
    pub host: Option<String>,
    pub port: Option<u16>,
    pub cancel: Arc<AtomicBool>,
}

impl Default for PairingInner {
    fn default() -> Self {
        PairingInner {
            phase: PHASE_IDLE.into(),
            host: None,
            port: None,
            cancel: Arc::new(AtomicBool::new(false)),
        }
    }
}

pub struct PairingState(pub Arc<Mutex<PairingInner>>);

impl PairingState {
    pub fn new() -> Self {
        PairingState(Arc::new(Mutex::new(PairingInner::default())))
    }
}

// ---------------------------------------------------------------------------
// Status DTO returned by get_pairing_status
// ---------------------------------------------------------------------------

#[derive(serde::Serialize)]
pub struct PairingStatus {
    pub phase: String,
}

// ---------------------------------------------------------------------------
// Keyring helpers (NEVER log token value or keyring errors)
// ---------------------------------------------------------------------------

pub fn get_keyring_token() -> Option<String> {
    Entry::new(KEYRING_SERVICE, KEYRING_USER)
        .ok()
        .and_then(|e| e.get_password().ok())
        .filter(|t| !t.is_empty() && !t.chars().any(|c| c.is_whitespace()))
}

pub fn delete_keyring_token() {
    if let Ok(entry) = Entry::new(KEYRING_SERVICE, KEYRING_USER) {
        let _ = entry.delete_credential();
    }
}

fn store_keyring_token(token: &str) -> bool {
    if token.is_empty() || token.chars().any(|c| c.is_whitespace()) {
        return false;
    }
    Entry::new(KEYRING_SERVICE, KEYRING_USER)
        .map(|e| e.set_password(token).is_ok())
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------
// URL builder — wraps IPv6 addresses in brackets
// ---------------------------------------------------------------------------

pub fn build_base_url(ip: &str, port: u16) -> String {
    if ip.contains(':') {
        format!("http://[{}]:{}", ip, port)
    } else {
        format!("http://{}:{}", ip, port)
    }
}

// ---------------------------------------------------------------------------
// Event emitter (pub so upload.rs can reuse)
// ---------------------------------------------------------------------------

pub fn emit_pairing_state(app_handle: &tauri::AppHandle, phase: &str, error_code: Option<&str>) {
    let payload = match error_code {
        Some(code) => serde_json::json!({ "phase": phase, "error_code": code }),
        None => serde_json::json!({ "phase": phase }),
    };
    let _ = app_handle.emit("pairing-state", payload);
}

// ---------------------------------------------------------------------------
// Body error parser — reads {"error":"<code>"} FIRST (never branch on status)
// If unparseable → None (caller uses generic fallback, never raw JSON)
// ---------------------------------------------------------------------------

pub fn parse_body_error_code(body: &[u8]) -> Option<String> {
    let value: serde_json::Value = serde_json::from_slice(body).ok()?;
    value
        .get(ERROR_KEY)
        .and_then(|e| e.as_str())
        .map(|s| s.to_string())
}

// ---------------------------------------------------------------------------
// State transition helpers (lock + emit)
// ---------------------------------------------------------------------------

fn transition_to_failed(
    inner: &Arc<Mutex<PairingInner>>,
    app_handle: &tauri::AppHandle,
    code: &str,
) {
    if let Ok(mut g) = inner.lock() {
        g.phase = PHASE_FAILED.into();
        g.host = None;
        g.port = None;
    }
    emit_pairing_state(app_handle, PHASE_FAILED, Some(code));
}

/// Atomically checks cancel flag UNDER THE MUTEX and, if not cancelled,
/// transitions to PAIRED. Returns true if transitioned, false if cancelled.
///
/// G5 (cancel TOCTOU): The cancel flag check and the phase=PAIRED write happen
/// inside the same lock acquisition, so cancel_pairing (which also holds the lock
/// while setting cancel=true) cannot race with this.
///
/// Caller MUST delete any just-stored keyring token when this returns false.
fn try_transition_to_paired(
    inner: &Arc<Mutex<PairingInner>>,
    app_handle: &tauri::AppHandle,
    cancel: &AtomicBool,
    host: String,
    port: u16,
) -> bool {
    {
        let mut g = inner.lock().unwrap_or_else(|e| e.into_inner());
        // Re-check cancel inside the same critical section that would set PAIRED
        if cancel.load(Ordering::Acquire) {
            return false;
        }
        g.phase = PHASE_PAIRED.into();
        g.host = Some(host);
        g.port = Some(port);
    }
    emit_pairing_state(app_handle, PHASE_PAIRED, None);
    true
}

// ---------------------------------------------------------------------------
// Pairing thread
// ---------------------------------------------------------------------------

fn pairing_thread(
    ip: String,
    port: u16,
    cancel: Arc<AtomicBool>,
    inner: Arc<Mutex<PairingInner>>,
    app_handle: tauri::AppHandle,
) {
    let base_url = build_base_url(&ip, port);

    let client = match reqwest::blocking::Client::builder().build() {
        Ok(c) => c,
        Err(_) => {
            transition_to_failed(&inner, &app_handle, ERROR_INTERNAL);
            return;
        }
    };

    // ── Optimisation: if keyring already has a token, try whoami first ──
    if let Some(token) = get_keyring_token() {
        let whoami_url = format!("{}{}", base_url, WHO_AM_I_ROUTE);
        let auth_value = format!("{}{}", AUTH_HEADER_PREFIX, token);
        match client
            .get(&whoami_url)
            .header(AUTH_HEADER_NAME, auth_value)
            .timeout(Duration::from_secs(10))
            .send()
        {
            Ok(resp) if resp.status().as_u16() == 200 => {
                // try_transition_to_paired checks cancel under the mutex (G5)
                // Pre-existing token stays in keyring even if cancelled
                let _ = try_transition_to_paired(&inner, &app_handle, &cancel, ip, port);
                return;
            }
            Ok(resp) if resp.status().as_u16() == 401 => {
                delete_keyring_token();
                // fall through to pair/request
            }
            _ => {
                // network error or unexpected status — fall through
            }
        }
    }

    // ── Check cancel before making the long 70-second request ──
    if cancel.load(Ordering::Acquire) {
        return;
    }

    // ── requestedAtEpochMillis MUST be > 0 ──
    let now_millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    if now_millis == 0 {
        transition_to_failed(&inner, &app_handle, ERROR_BAD_REQUEST);
        return;
    }

    // ── Device name (hostname crate; "Desktop" fallback) ──
    let device_name = hostname::get()
        .ok()
        .map(|h| h.to_string_lossy().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Desktop".to_string());

    let pair_url = format!("{}{}", base_url, PAIR_REQUEST_ROUTE);
    let body = serde_json::json!({
        DEVICE_NAME_KEY: device_name,
        REQUESTED_AT_KEY: now_millis,
    });

    let response = match client
        .post(&pair_url)
        .timeout(Duration::from_secs(PAIRING_WAIT_SECS))
        .json(&body)
        .send()
    {
        Ok(r) => r,
        Err(e) => {
            let code = if e.is_timeout() {
                PAIRING_ERROR_TIMEOUT
            } else {
                ERROR_INTERNAL
            };
            transition_to_failed(&inner, &app_handle, code);
            return;
        }
    };

    let status = response.status().as_u16();
    let body_bytes = response.bytes().unwrap_or_default();

    // ── Re-check cancel AFTER reading body bytes, immediately before token persist (G5) ──
    if cancel.load(Ordering::Acquire) {
        return;
    }

    if status == 200 {
        let token = serde_json::from_slice::<serde_json::Value>(&body_bytes)
            .ok()
            .and_then(|v| {
                v.get(TOKEN_KEY)
                    .and_then(|t| t.as_str())
                    .map(|s| s.to_string())
            });

        match token {
            Some(ref t) if !t.is_empty() && !t.chars().any(|c| c.is_whitespace()) => {
                if !store_keyring_token(t) {
                    transition_to_failed(&inner, &app_handle, ERROR_INTERNAL);
                    return;
                }
                // G5: check cancel UNDER THE MUTEX via try_transition_to_paired.
                // If cancelled after token was stored, delete it.
                if !try_transition_to_paired(&inner, &app_handle, &cancel, ip, port) {
                    delete_keyring_token();
                    // State is already idle (cancel_pairing set it before releasing the lock)
                }
            }
            _ => {
                // Token missing or contains whitespace — reject
                transition_to_failed(&inner, &app_handle, ERROR_BAD_REQUEST);
            }
        }
    } else {
        let code = parse_body_error_code(&body_bytes)
            .unwrap_or_else(|| ERROR_INTERNAL.to_string());
        transition_to_failed(&inner, &app_handle, &code);
    }
}

// ---------------------------------------------------------------------------
// Tauri commands
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn request_pairing(
    state: State<'_, PairingState>,
    app_handle: tauri::AppHandle,
    ip: String,
    port: u16,
) -> Result<(), String> {
    let cancel = Arc::new(AtomicBool::new(false));
    {
        let mut g = state.0.lock().map_err(|_| "lock poisoned")?;
        if g.phase == PHASE_WAITING {
            // Stable error code — not a Rust panic
            return Err(PAIRING_ERROR_IN_PROGRESS.into());
        }
        g.phase = PHASE_WAITING.into();
        g.host = None;
        g.port = None;
        g.cancel = Arc::clone(&cancel);
    }

    // Emit waiting before spawning so JS sees the state immediately
    emit_pairing_state(&app_handle, PHASE_WAITING, None);

    let inner_arc = Arc::clone(&state.0);
    std::thread::spawn(move || {
        pairing_thread(ip, port, cancel, inner_arc, app_handle);
    });

    Ok(())
}

#[tauri::command]
pub fn cancel_pairing(
    state: State<'_, PairingState>,
    app_handle: tauri::AppHandle,
) -> Result<(), String> {
    {
        let mut g = state.0.lock().map_err(|_| "lock poisoned")?;
        if g.phase != PHASE_WAITING {
            return Ok(()); // nothing to cancel
        }
        // Setting cancel=true and phase=IDLE under the SAME lock prevents the pairing
        // thread from sneaking in a phase=PAIRED transition after we release (G5)
        g.cancel.store(true, Ordering::Release);
        g.phase = PHASE_IDLE.into();
        g.host = None;
        g.port = None;
    }
    emit_pairing_state(&app_handle, PHASE_IDLE, None);
    Ok(())
}

#[tauri::command]
pub fn get_pairing_status(state: State<'_, PairingState>) -> Result<PairingStatus, String> {
    let g = state.0.lock().map_err(|_| "lock poisoned")?;
    Ok(PairingStatus {
        phase: g.phase.clone(),
    })
}
