use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::fs::File;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::sync::{mpsc, Mutex};
use tauri::{Emitter, State};

// ---------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------

struct RecordingHandle {
    stop_tx: mpsc::SyncSender<()>,
    result_rx: mpsc::Receiver<Result<String, String>>,
}

pub struct RecorderState {
    handle: Mutex<Option<RecordingHandle>>,
    /// Full absolute path of the last successfully saved recording.
    /// Set ONLY on successful stop_recording. NEVER cleared on start_recording.
    pub last_path: Mutex<Option<String>>,
}

impl RecorderState {
    pub fn new() -> Self {
        RecorderState {
            handle: Mutex::new(None),
            last_path: Mutex::new(None),
        }
    }
}

// ---------------------------------------------------------------------------
// Sample → i16 conversion
// ---------------------------------------------------------------------------

trait SampleToI16: Copy {
    fn to_i16_sample(self) -> i16;
}

impl SampleToI16 for f32 {
    #[inline]
    fn to_i16_sample(self) -> i16 {
        // Scale by 32768 so full-scale ±1.0 maps to the full i16 range
        (self * 32768.0_f32).clamp(i16::MIN as f32, i16::MAX as f32) as i16
    }
}

impl SampleToI16 for f64 {
    #[inline]
    fn to_i16_sample(self) -> i16 {
        (self * 32768.0_f64).clamp(i16::MIN as f64, i16::MAX as f64) as i16
    }
}

impl SampleToI16 for i8 {
    fn to_i16_sample(self) -> i16 {
        (self as i16) * 256
    }
}

impl SampleToI16 for i16 {
    fn to_i16_sample(self) -> i16 {
        self
    }
}

impl SampleToI16 for i32 {
    fn to_i16_sample(self) -> i16 {
        // Round-to-nearest via i64 to avoid wrapping on i32::MAX + 0x8000
        ((self as i64 + 0x8000) >> 16).clamp(i16::MIN as i64, i16::MAX as i64) as i16
    }
}

impl SampleToI16 for u8 {
    fn to_i16_sample(self) -> i16 {
        (self as i16 - 128) * 256
    }
}

impl SampleToI16 for u16 {
    fn to_i16_sample(self) -> i16 {
        (self as i32 - 32768) as i16
    }
}

impl SampleToI16 for u32 {
    fn to_i16_sample(self) -> i16 {
        // Center in signed domain, then round-to-nearest
        let signed = self as i64 - 2_147_483_648_i64;
        ((signed + 0x8000) >> 16).clamp(i16::MIN as i64, i16::MAX as i64) as i16
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

type SharedWriter = std::sync::Arc<Mutex<Option<hound::WavWriter<BufWriter<File>>>>>;

/// Take and drop the WavWriter (which closes BufWriter<File>), then delete the file.
/// Handles poisoned mutex via into_inner so the file handle is always closed
/// before remove_file — required on Windows where open handles block deletion.
fn close_and_delete(writer: &SharedWriter, path: &Path) {
    let mut guard = match writer.lock() {
        Ok(g) => g,
        Err(e) => e.into_inner(), // force-unlock even if poisoned
    };
    drop(guard.take()); // WavWriter dropped → BufWriter flushed → File closed
    drop(guard); // release mutex
    let _ = std::fs::remove_file(path);
}

/// Atomically claim a unique WAV filename in `dir` using create_new.
/// Returns (path, open File) exclusively owned by the caller.
/// Suffix `_2`…`_99` is appended on collision (like Android claimUniqueDestFile).
fn claim_unique_final_path(dir: &Path, ts: u128) -> std::io::Result<(PathBuf, File)> {
    let stem = format!("(C2V)recording_{}", ts);
    let try_new = |p: &PathBuf| {
        std::fs::OpenOptions::new()
            .write(true)
            .create_new(true) // atomic: fails with AlreadyExists if file exists
            .open(p)
    };

    let base = dir.join(format!("{}.wav", stem));
    match try_new(&base) {
        Ok(f) => return Ok((base, f)),
        Err(e) if e.kind() != std::io::ErrorKind::AlreadyExists => return Err(e),
        _ => {}
    }

    for n in 2u32..=99 {
        let p = dir.join(format!("{}_{}.wav", stem, n));
        match try_new(&p) {
            Ok(f) => return Ok((p, f)),
            Err(e) if e.kind() != std::io::ErrorKind::AlreadyExists => return Err(e),
            _ => {}
        }
    }

    Err(std::io::Error::new(
        std::io::ErrorKind::AlreadyExists,
        "All filename slots taken after 99 tries",
    ))
}

// ---------------------------------------------------------------------------
// Tauri commands
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn start_recording(
    state: State<'_, RecorderState>,
    app_handle: tauri::AppHandle, // auto-injected by Tauri
) -> Result<(), String> {
    let mut guard = state.handle.lock().map_err(|_| "State lock poisoned")?;
    if guard.is_some() {
        return Err("Already recording".into());
    }

    let (ready_tx, ready_rx) = mpsc::sync_channel::<Result<(), String>>(0);
    let (stop_tx, stop_rx) = mpsc::sync_channel::<()>(0);
    let (result_tx, result_rx) = mpsc::channel::<Result<String, String>>();

    std::thread::spawn(move || {
        recording_thread(ready_tx, stop_rx, result_tx, app_handle);
    });

    // Block until the thread confirms the mic opened (or fails)
    let ready = ready_rx
        .recv()
        .map_err(|_| "Recording thread died unexpectedly".to_string())?;
    ready?;

    *guard = Some(RecordingHandle { stop_tx, result_rx });
    Ok(())
}

#[tauri::command]
pub fn stop_recording(state: State<'_, RecorderState>) -> Result<String, String> {
    let mut guard = state.handle.lock().map_err(|_| "State lock poisoned")?;
    let handle = guard.take().ok_or("Not currently recording")?;
    drop(guard); // release state lock before blocking

    // Signal stop (ignore if thread already exited due to stream error)
    let _ = handle.stop_tx.send(());

    // 30-second timeout guards against a hung thread; N1: stable error code
    let result = handle
        .result_rx
        .recv_timeout(std::time::Duration::from_secs(30))
        .map_err(|_| "save_timeout".to_string())?;

    // Set last_path ONLY on success — never clear it on start_recording
    if let Ok(ref path) = result {
        if let Ok(mut lp) = state.last_path.lock() {
            *lp = Some(path.clone());
        }
    }

    result
}

/// Returns the basename of the last successfully saved recording, or None.
/// Never returns an absolute path to JS (security: no username in path).
#[tauri::command]
pub fn get_last_recording_path(state: State<'_, RecorderState>) -> Option<String> {
    let g = state.last_path.lock().ok()?;
    let path_str = g.as_deref()?;
    Path::new(path_str)
        .file_name()
        .and_then(|n| n.to_str())
        .map(|s| s.to_string())
}

// ---------------------------------------------------------------------------
// Recording thread
// ---------------------------------------------------------------------------

fn recording_thread(
    ready_tx: mpsc::SyncSender<Result<(), String>>,
    stop_rx: mpsc::Receiver<()>,
    result_tx: mpsc::Sender<Result<String, String>>,
    app_handle: tauri::AppHandle,
) {
    // ---- Device & config ----
    let host = cpal::default_host();

    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            let _ = ready_tx.send(Err(
                "No default input device found. Please connect a microphone.".into(),
            ));
            return;
        }
    };

    let supported_config = match device.default_input_config() {
        Ok(c) => c,
        Err(e) => {
            let _ = ready_tx.send(Err(format!("Cannot open microphone: {}", e)));
            return;
        }
    };

    let channels = supported_config.channels();
    let sample_rate = supported_config.sample_rate().0;
    let sample_format = supported_config.sample_format();
    let stream_config = supported_config.config();

    // ---- Output directory ----
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();

    let output_dir = {
        let home = std::env::var("USERPROFILE")
            .or_else(|_| std::env::var("HOME"))
            .map(PathBuf::from)
            .unwrap_or_else(|_| std::env::temp_dir());
        home.join("Documents").join("C2V Recordings")
    };

    if let Err(e) = std::fs::create_dir_all(&output_dir) {
        let _ = ready_tx.send(Err(format!("Cannot create output directory: {}", e)));
        return;
    }

    // ---- Atomically claim unique final path — no temp file needed ----
    let (final_path, final_file) = match claim_unique_final_path(&output_dir, ts) {
        Ok(p) => p,
        Err(e) => {
            let _ = ready_tx.send(Err(format!("Cannot create output file: {}", e)));
            return;
        }
    };

    // ---- WAV writer directly on the claimed file ----
    let spec = hound::WavSpec {
        channels,
        sample_rate,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };

    let wav_writer = match hound::WavWriter::new(BufWriter::new(final_file), spec) {
        Ok(w) => w,
        Err(e) => {
            // BufWriter<File> was consumed and dropped by hound — file is closed
            let _ = std::fs::remove_file(&final_path);
            let _ = ready_tx.send(Err(format!("Cannot create WAV writer: {}", e)));
            return;
        }
    };

    let shared_writer: SharedWriter = std::sync::Arc::new(Mutex::new(Some(wav_writer)));

    // ---- Stream error channel (error callback → recording thread) ----
    let (stream_err_tx, stream_err_rx) = mpsc::channel::<String>();

    // ---- Build capture stream ----
    let writer_for_cb = shared_writer.clone();
    let stream = match build_stream_any(
        &device,
        &stream_config,
        sample_format,
        writer_for_cb,
        stream_err_tx,
    ) {
        Ok(s) => s,
        Err(e) => {
            // Close file handle before deleting (Windows requires this)
            close_and_delete(&shared_writer, &final_path);
            let _ = ready_tx.send(Err(e));
            return;
        }
    };

    if let Err(e) = stream.play() {
        close_and_delete(&shared_writer, &final_path);
        let _ = ready_tx.send(Err(format!("Cannot start capture: {}", e)));
        return;
    }

    // Signal caller: mic is open and recording has started
    let _ = ready_tx.send(Ok(()));

    // ---- Wait for stop signal or async stream error ----
    //
    // Poll stream_err_rx (non-blocking) each iteration so a hardware error
    // is detected within ~100 ms instead of blocking forever on stop_rx.
    let stream_error: Option<String> = loop {
        if let Ok(err_msg) = stream_err_rx.try_recv() {
            break Some(err_msg);
        }
        match stop_rx.recv_timeout(std::time::Duration::from_millis(100)) {
            Ok(()) | Err(mpsc::RecvTimeoutError::Disconnected) => break None,
            Err(mpsc::RecvTimeoutError::Timeout) => {} // continue polling
        }
    };

    // Drop stream — cpal joins its callback thread before returning
    drop(stream);

    if let Some(_err_msg) = stream_error {
        // Close file handle first, then delete (Windows: open handle blocks delete)
        close_and_delete(&shared_writer, &final_path);
        // N1: emit stable error code — never expose raw cpal/IO strings to JS
        let _ = result_tx.send(Err("stream_error".into()));
        let _ = app_handle.emit("recording-error", "stream_error");
        return;
    }

    // ---- Finalize WAV (writes RIFF size fields into the header) ----
    let writer_opt = match shared_writer.lock() {
        Ok(mut g) => g.take(),
        Err(e) => {
            // Poisoned mutex: force-unlock, close file, delete
            let mut g = e.into_inner();
            drop(g.take()); // closes File
            drop(g);
            let _ = std::fs::remove_file(&final_path);
            let _ = result_tx.send(Err("Writer lock poisoned during finalize".into()));
            return;
        }
    };

    if let Some(writer) = writer_opt {
        if let Err(e) = writer.finalize() {
            // writer consumed by finalize; hound closes the file handle internally
            let _ = std::fs::remove_file(&final_path);
            let _ = result_tx.send(Err(format!("Cannot finalize WAV: {}", e)));
            return;
        }
    }

    let _ = result_tx.send(Ok(final_path.to_string_lossy().to_string()));
}

// ---------------------------------------------------------------------------
// Stream building
// ---------------------------------------------------------------------------

fn build_stream_any(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    format: cpal::SampleFormat,
    writer: SharedWriter,
    err_tx: mpsc::Sender<String>,
) -> Result<cpal::Stream, String> {
    use cpal::SampleFormat;
    match format {
        SampleFormat::F32 => build_typed::<f32>(device, config, writer, err_tx),
        SampleFormat::F64 => build_typed::<f64>(device, config, writer, err_tx),
        SampleFormat::I8  => build_typed::<i8>(device, config, writer, err_tx),
        SampleFormat::I16 => build_typed::<i16>(device, config, writer, err_tx),
        SampleFormat::I32 => build_typed::<i32>(device, config, writer, err_tx),
        SampleFormat::U8  => build_typed::<u8>(device, config, writer, err_tx),
        SampleFormat::U16 => build_typed::<u16>(device, config, writer, err_tx),
        SampleFormat::U32 => build_typed::<u32>(device, config, writer, err_tx),
        other => Err(format!("Unsupported sample format: {:?}", other)),
    }
}

fn build_typed<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    writer: SharedWriter,
    err_tx: mpsc::Sender<String>,
) -> Result<cpal::Stream, String>
where
    T: cpal::SizedSample + SampleToI16,
{
    // Clone so both callbacks have their own Sender (Sender<T>: Clone)
    let data_err_tx = err_tx.clone();

    device
        .build_input_stream(
            config,
            move |data: &[T], _info: &cpal::InputCallbackInfo| {
                // G4: treat mutex poison the same as a fatal write error
                let mut guard = match writer.lock() {
                    Ok(g) => g,
                    Err(_poisoned) => {
                        let _ = data_err_tx
                            .send("Writer mutex poisoned in audio callback".to_string());
                        return;
                    }
                };

                // Writer already taken after a prior error — stop silently
                if guard.is_none() {
                    return;
                }

                // G1: propagate write_sample failures instead of swallowing them
                let mut io_err: Option<String> = None;
                if let Some(w) = guard.as_mut() {
                    for &sample in data {
                        if let Err(e) = w.write_sample(sample.to_i16_sample()) {
                            io_err = Some(format!("write_sample failed: {}", e));
                            break;
                        }
                    }
                }
                if let Some(msg) = io_err {
                    // Drop WavWriter here so the file handle is closed before the
                    // recording thread calls remove_file (required on Windows)
                    *guard = None;
                    drop(guard);
                    let _ = data_err_tx.send(msg);
                }
            },
            move |err| {
                eprintln!("[C2V] Capture stream error: {}", err);
                let _ = err_tx.send(format!("{}", err));
            },
            None,
        )
        .map_err(|e| format!("Cannot open microphone stream: {}", e))
}
