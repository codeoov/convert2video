use std::fs::File;
use std::io::Read;
use std::path::Path;
use std::sync::{Arc, Mutex};
use tauri::State;

use crate::audio::RecorderState;
use crate::pairing::{
    build_base_url, delete_keyring_token, emit_pairing_state, get_keyring_token,
    parse_body_error_code, PairingInner, PairingState, AUTH_HEADER_NAME, AUTH_HEADER_PREFIX,
    ERROR_INTERNAL, ERROR_UNAUTHORIZED, PAIRING_ERROR_TIMEOUT, PHASE_IDLE, PHASE_PAIRED,
    PHASE_SUCCESS, PHASE_UPLOADING,
};

// ---------------------------------------------------------------------------
// Named constants — SSOT; no inline string literals in logic
// ---------------------------------------------------------------------------

const UPLOAD_ROUTE: &str = "/upload";
const UPLOAD_FILE_FIELD: &str = "file";
const UPLOAD_ORIGINAL_FILE_NAME_FIELD: &str = "originalFileName";
const UPLOAD_FORMAT_FIELD: &str = "format";
const UPLOAD_DURATION_MS_FIELD: &str = "durationMs";
const UPLOAD_FORMAT_WAV: &str = "wav";
const UPLOAD_MIME_WAV: &str = "audio/wav";
pub const MAX_UPLOAD_BYTES: u64 = 500 * 1024 * 1024;

/// Server-side "too_large" error code — SSOT constant (Android sends this in JSON body)
#[allow(dead_code)]
const ERROR_PAYLOAD_TOO_LARGE: &str = "too_large";
const ERROR_NO_RECORDING: &str = "no_recording_available";
const ERROR_WAV_INVALID: &str = "wav_invalid";
const ERROR_FILE_TOO_LARGE: &str = "file_too_large";

// ---------------------------------------------------------------------------
// WAV header validation — checks RIFF (0-3) and WAVE (8-11) magic bytes
// Returns Ok(true) = valid WAV, Ok(false) = wrong magic, Err = I/O failure
// ---------------------------------------------------------------------------

fn check_wav_header(path: &str) -> Result<bool, ()> {
    let mut f = File::open(path).map_err(|_| ())?;
    let mut header = [0u8; 12];
    f.read_exact(&mut header).map_err(|_| ())?;
    let valid = &header[0..4] == b"RIFF" && &header[8..12] == b"WAVE";
    Ok(valid)
}

// ---------------------------------------------------------------------------
// WAV duration helper — decimal milliseconds string, "0" on any failure
// ---------------------------------------------------------------------------

fn wav_duration_ms_str(path: &str) -> String {
    hound::WavReader::open(path)
        .ok()
        .map(|r| {
            let spec = r.spec();
            let duration_secs = r.duration() as f64 / spec.sample_rate as f64;
            (duration_secs * 1000.0) as u64
        })
        .unwrap_or(0)
        .to_string()
}

// ---------------------------------------------------------------------------
// Stay-paired error helper (G4):
//   Emits ONE "paired" event WITH error_code so the error message remains
//   visible in JS without being cleared by a subsequent error-free paired event.
//   "Failed upload should remain paired unless 401."
// ---------------------------------------------------------------------------

fn restore_paired_with_error(
    inner: &Arc<Mutex<PairingInner>>,
    app_handle: &tauri::AppHandle,
    code: &str,
) {
    if let Ok(mut g) = inner.lock() {
        g.phase = PHASE_PAIRED.into();
        // host and port are preserved so subsequent sends work
    }
    // Single event: phase=paired + error_code — JS shows error AND keeps send enabled
    emit_pairing_state(app_handle, PHASE_PAIRED, Some(code));
}

// ---------------------------------------------------------------------------
// Upload thread
// ---------------------------------------------------------------------------

fn upload_thread(
    host: String,
    port: u16,
    last_path: String,
    inner: Arc<Mutex<PairingInner>>,
    app_handle: tauri::AppHandle,
) {
    // Mark uploading — JS clears previous error on "uploading" (new attempt)
    if let Ok(mut g) = inner.lock() {
        g.phase = PHASE_UPLOADING.into();
    }
    emit_pairing_state(&app_handle, PHASE_UPLOADING, None);

    // ── WAV header validation (local, no HTTP) ──
    match check_wav_header(&last_path) {
        Err(_) => {
            restore_paired_with_error(&inner, &app_handle, ERROR_NO_RECORDING);
            return;
        }
        Ok(false) => {
            restore_paired_with_error(&inner, &app_handle, ERROR_WAV_INVALID);
            return;
        }
        Ok(true) => {}
    }

    // ── File size check (local, no HTTP) ──
    // N3: metadata() Err (e.g. file deleted between check and upload) is a no-recording error
    let file_len = match std::fs::metadata(&last_path) {
        Ok(m) => m.len(),
        Err(_) => {
            restore_paired_with_error(&inner, &app_handle, ERROR_NO_RECORDING);
            return;
        }
    };
    if file_len > MAX_UPLOAD_BYTES {
        restore_paired_with_error(&inner, &app_handle, ERROR_FILE_TOO_LARGE);
        return;
    }

    // ── Duration string (best-effort; "0" on failure — still upload) ──
    let duration_str = wav_duration_ms_str(&last_path);

    // ── Basename only — never transmit absolute path over the network ──
    let basename = Path::new(&last_path)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("recording.wav")
        .to_string();

    // ── Retrieve token ──
    let token = match get_keyring_token() {
        Some(t) => t,
        None => {
            if let Ok(mut g) = inner.lock() {
                g.phase = PHASE_IDLE.into();
                g.host = None;
                g.port = None;
            }
            emit_pairing_state(&app_handle, PHASE_IDLE, Some(ERROR_UNAUTHORIZED));
            return;
        }
    };

    let auth_value = format!("{}{}", AUTH_HEADER_PREFIX, token);

    // ── Build HTTP client ──
    let client = match reqwest::blocking::Client::builder().build() {
        Ok(c) => c,
        Err(_) => {
            restore_paired_with_error(&inner, &app_handle, ERROR_INTERNAL);
            return;
        }
    };

    // ── Open file for streaming (do NOT read into Vec) ──
    let file = match File::open(&last_path) {
        Ok(f) => f,
        Err(_) => {
            restore_paired_with_error(&inner, &app_handle, ERROR_NO_RECORDING);
            return;
        }
    };

    // ── Build multipart part with MIME type (G6) ──
    // mime_str("audio/wav") is infallible in practice; on parse error emit ERROR_INTERNAL.
    let part = match reqwest::blocking::multipart::Part::reader(file)
        .file_name(basename.clone())
        .mime_str(UPLOAD_MIME_WAV)
    {
        Ok(p) => p,
        Err(_) => {
            // "audio/wav" parse failure is theoretically impossible but guarded.
            // Emit ERROR_INTERNAL and return; paired state preserved by restore_paired_with_error.
            restore_paired_with_error(&inner, &app_handle, ERROR_INTERNAL);
            return;
        }
    };

    let form = reqwest::blocking::multipart::Form::new()
        .part(UPLOAD_FILE_FIELD, part)
        .text(UPLOAD_ORIGINAL_FILE_NAME_FIELD, basename)
        .text(UPLOAD_FORMAT_FIELD, UPLOAD_FORMAT_WAV)
        .text(UPLOAD_DURATION_MS_FIELD, duration_str);

    // ── HTTP upload ──
    let upload_url = format!("{}{}", build_base_url(&host, port), UPLOAD_ROUTE);
    let response = match client
        .post(&upload_url)
        .header(AUTH_HEADER_NAME, auth_value)
        .multipart(form)
        .send()
    {
        Ok(r) => r,
        Err(e) => {
            // G2: use named constant PAIRING_ERROR_TIMEOUT — no inline "timeout"
            let code = if e.is_timeout() {
                PAIRING_ERROR_TIMEOUT
            } else {
                ERROR_INTERNAL
            };
            restore_paired_with_error(&inner, &app_handle, code);
            return;
        }
    };

    let status = response.status().as_u16();
    let body_bytes = response.bytes().unwrap_or_default();

    match status {
        200 => {
            // Success — stay paired with phase "success"; send button remains enabled
            if let Ok(mut g) = inner.lock() {
                g.phase = PHASE_SUCCESS.into();
            }
            emit_pairing_state(&app_handle, PHASE_SUCCESS, None);
        }
        401 => {
            // Delete token and go idle
            delete_keyring_token();
            if let Ok(mut g) = inner.lock() {
                g.phase = PHASE_IDLE.into();
                g.host = None;
                g.port = None;
            }
            emit_pairing_state(&app_handle, PHASE_IDLE, Some(ERROR_UNAUTHORIZED));
        }
        _ => {
            // Parse error code from body FIRST (never branch on status alone).
            // Server may return ERROR_PAYLOAD_TOO_LARGE ("too_large") or other codes.
            let code = parse_body_error_code(&body_bytes)
                .unwrap_or_else(|| ERROR_INTERNAL.to_string());
            restore_paired_with_error(&inner, &app_handle, &code);
        }
    }
}

// ---------------------------------------------------------------------------
// Tauri command
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn send_last_recording(
    pairing: State<'_, PairingState>,
    recorder: State<'_, RecorderState>,
    app_handle: tauri::AppHandle,
) -> Result<(), String> {
    // ── Get host/port from pairing state ──
    let (host, port) = {
        let g = pairing.0.lock().map_err(|_| "lock poisoned")?;
        if g.phase != PHASE_PAIRED && g.phase != PHASE_SUCCESS {
            return Err("not_paired".into());
        }
        match (&g.host, g.port) {
            (Some(h), Some(p)) => (h.clone(), p),
            _ => return Err("not_paired".into()),
        }
    };

    // ── Get full path from recorder state ──
    let last_path = {
        let g = recorder.last_path.lock().map_err(|_| "lock poisoned")?;
        g.clone().ok_or_else(|| ERROR_NO_RECORDING.to_string())?
    };

    // ── Spawn upload thread; return immediately ──
    let inner_arc = Arc::clone(&pairing.0);
    std::thread::spawn(move || {
        upload_thread(host, port, last_path, inner_arc, app_handle);
    });

    Ok(())
}
