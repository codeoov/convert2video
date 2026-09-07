// Tauri v2 with withGlobalTauri: true injects window.__TAURI__ before scripts run
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const btnStart  = document.getElementById('btn-start');
const btnStop   = document.getElementById('btn-stop');
const timerEl   = document.getElementById('timer');
const statusEl  = document.getElementById('status');
const deviceList = document.getElementById('device-list');
const deviceHint = document.getElementById('device-hint');

// Pairing UI elements
const pairingSection  = document.getElementById('pairing-section');
const pairingMsg      = document.getElementById('pairing-msg');
const pairingError    = document.getElementById('pairing-error');
const btnCancelPair   = document.getElementById('btn-cancel-pair');
const btnSend         = document.getElementById('btn-send');

// G3: Use a start timestamp instead of a counter to avoid setInterval drift
let timerInterval    = null;
let recordingStartMs = 0;
let finalElapsedSec  = 0; // captured when stopTimer() is called

// G5: Store unlisten functions so we can tear down listeners properly
let unlistenRecordingError = null;
let unlistenDeviceFound    = null;
let unlistenDeviceLost     = null;
let unlistenBrowserError   = null;
let unlistenPairingState   = null;

// Device map: key = fullname (mDNS identity), value = { name, display, ip, port }
const devices = new Map();

// ── Pairing state tracked by the last pairing-state event ──
let currentPairingPhase = 'idle';
let lastRecordingBasename = null; // null = no recording yet

// Error code → Korean user message (NEVER show raw English/JSON from Rust)
const ERROR_MESSAGES = {
  // Recording errors (audio.rs stable codes)
  stream_error:         '녹음 중 오류가 발생했습니다',
  save_timeout:         '녹음 저장에 실패했습니다 (시간 초과)',
  // Pairing errors (pairing.rs / upload.rs stable codes)
  denied:               '연결이 거부되었습니다',
  timeout:              '승인 시간이 초과되었습니다',
  pairing_in_progress:  '다른 기기가 승인 대기 중입니다',
  app_backgrounded:     '앱이 백그라운드로 전환되었습니다',
  service_stopped:      'Android 서버가 중지되었습니다',
  bad_request:          '요청 오류 (내부)',
  unauthorized:         '인증이 만료되었습니다 — 재연결 필요',
  too_large:            '파일이 너무 큽니다 (최대 500 MB)',
  file_too_large:       '파일이 너무 큽니다 (최대 500 MB)',
  internal:             '서버 오류가 발생했습니다',
  no_recording_available: '전송할 녹음 파일이 없습니다',
  wav_invalid:          'WAV 파일이 아닙니다 (RIFF/WAVE 검증 실패)',
};

// N1: Korean fallback for any unknown code — never show raw English from Rust
function getErrorMessage(code) {
  return ERROR_MESSAGES[code] || '작업에 실패했습니다';
}

function setStatus(msg, type = '') {
  statusEl.textContent = msg;
  statusEl.className = type;
}

// Show mm:ss, or h:mm:ss when elapsed >= 1 hour
function formatTime(sec) {
  if (sec >= 3600) {
    const h = Math.floor(sec / 3600);
    const m = String(Math.floor((sec % 3600) / 60)).padStart(2, '0');
    const s = String(sec % 60).padStart(2, '0');
    return `${h}:${m}:${s}`;
  }
  const m = String(Math.floor(sec / 60)).padStart(2, '0');
  const s = String(sec % 60).padStart(2, '0');
  return `${m}:${s}`;
}

// G6: Extract just the filename (last path segment) from an absolute path
function fileNameOnly(path) {
  return path.split(/[\\/]/).pop() || path;
}

// Extract instance name from mDNS fullname by stripping the known service suffix.
// e.g. "My.Phone._c2vsync._tcp.local." → "My.Phone"
// Do NOT split on '.' — instance names can contain dots.
function instanceName(fullname) {
  for (const suffix of ['._c2vsync._tcp.local.', '._c2vsync._tcp.']) {
    if (fullname.endsWith(suffix)) {
      return fullname.slice(0, fullname.length - suffix.length) || fullname;
    }
  }
  return fullname;
}

function startTimer() {
  recordingStartMs = Date.now();
  finalElapsedSec  = 0;
  timerEl.textContent = formatTime(0);
  timerInterval = setInterval(() => {
    const sec = Math.floor((Date.now() - recordingStartMs) / 1000);
    timerEl.textContent = formatTime(sec);
  }, 500);
}

function stopTimer() {
  if (timerInterval) {
    finalElapsedSec = Math.floor((Date.now() - recordingStartMs) / 1000);
    clearInterval(timerInterval);
    timerInterval = null;
  }
}

// ── Pairing UI renderer ──────────────────────────────────────────
function updatePairingUI() {
  const phase = currentPairingPhase;
  const canSend = (phase === 'paired' || phase === 'success') && !!lastRecordingBasename;

  switch (phase) {
    case 'idle':
      pairingSection.style.display = 'none';
      btnCancelPair.style.display = 'none';
      btnSend.style.display = 'none';
      break;

    case 'waiting':
      pairingSection.style.display = '';
      pairingMsg.textContent = 'Android 앱에서 연결을 승인해 주세요...';
      btnCancelPair.style.display = '';
      btnSend.style.display = 'none';
      break;

    case 'paired':
      pairingSection.style.display = '';
      pairingMsg.textContent = '연결됨';
      btnCancelPair.style.display = 'none';
      btnSend.style.display = '';
      btnSend.disabled = !canSend;
      break;

    case 'uploading':
      pairingSection.style.display = '';
      pairingMsg.textContent = '전송 중...';
      btnCancelPair.style.display = 'none';
      btnSend.style.display = '';
      btnSend.disabled = true;
      break;

    case 'success':
      pairingSection.style.display = '';
      pairingMsg.textContent = '전송 완료 ✓';
      btnCancelPair.style.display = 'none';
      btnSend.style.display = '';
      btnSend.disabled = !canSend; // send button remains for another send
      break;

    case 'failed':
      pairingSection.style.display = '';
      pairingMsg.textContent = '연결 실패';
      btnCancelPair.style.display = 'none';
      btnSend.style.display = 'none';
      break;

    default:
      break;
  }
}

// ── Query last recording path (for send button state) ───────────
async function refreshLastRecordingPath() {
  try {
    lastRecordingBasename = await invoke('get_last_recording_path');
  } catch (_) {
    lastRecordingBasename = null;
    console.warn('[C2V] last recording path unavailable'); // N-G6: no err object/path in log
  }
  updatePairingUI();
}

// Rebuild #device-list DOM from the devices Map
function renderDeviceList() {
  deviceList.innerHTML = '';
  if (devices.size === 0) {
    deviceHint.style.display = '';
    return;
  }
  deviceHint.style.display = 'none';
  for (const [key, device] of devices) {
    const li = document.createElement('li');
    li.className = 'device-row';

    const nameSpan = document.createElement('span');
    nameSpan.className = 'device-name';
    nameSpan.textContent = device.display || instanceName(device.name);

    const ipSpan = document.createElement('span');
    ipSpan.className = 'device-ip';
    ipSpan.textContent = `${device.ip}:${device.port}`;

    // Connect button — invokes request_pairing; Rust handles all HTTP
    const connectBtn = document.createElement('button');
    connectBtn.className = 'btn-connect';
    connectBtn.textContent = '연결';
    connectBtn.addEventListener('click', async () => {
      pairingError.textContent = '';
      try {
        await invoke('request_pairing', { ip: device.ip, port: device.port });
      } catch (err) {
        showPairingInvokeError(err); // N-G5: deduplicated via shared helper
      }
    });

    li.appendChild(nameSpan);
    li.appendChild(ipSpan);
    li.appendChild(connectBtn);
    deviceList.appendChild(li);
  }
}

// G5: Register all event listeners once at startup and store unlisten handles
async function setupListeners() {
  unlistenRecordingError = await listen('recording-error', async (event) => {
    if (btnStop.disabled) return; // already handled
    stopTimer();
    btnStop.disabled = true;
    // N1: translate stable error code to Korean — never show raw cpal/Rust strings
    setStatus(getErrorMessage(String(event.payload)), 'error');
    try { await invoke('stop_recording'); } catch (_) {}
    btnStart.disabled = false;
  });

  unlistenDeviceFound = await listen('device-found', (event) => {
    const { name, display, ip, port } = event.payload;
    devices.set(name, { name, display, ip, port });
    renderDeviceList();
  });

  unlistenDeviceLost = await listen('device-lost', (event) => {
    const { name } = event.payload;
    devices.delete(name);
    renderDeviceList();
  });

  unlistenBrowserError = await listen('browser-error', (event) => {
    console.warn('[C2V] mDNS browser error:', String(event.payload));
    setStatus('기기 검색에 실패했습니다', 'error');
  });

  unlistenPairingState = await listen('pairing-state', (event) => {
    const { phase, error_code } = event.payload;
    currentPairingPhase = phase;

    if (error_code) {
      // Always show error when explicitly provided — regardless of phase.
      // This covers "paired" + error_code (failed upload, remain paired) so the
      // message stays visible; and "failed" + error_code (pairing rejected).
      pairingError.textContent = getErrorMessage(error_code);
      setStatus(getErrorMessage(error_code), 'error'); // N-G2: reflect error in main status
    } else if (phase === 'waiting' || phase === 'uploading' || phase === 'success') {
      // Clear on new pairing attempt, new upload attempt, and successful upload.
      // Do NOT clear merely because phase became "paired" with no error_code —
      // a previous upload error should stay visible until the next action.
      pairingError.textContent = '';
      // N-G3: update main status for upload lifecycle phases only
      if (phase === 'uploading') {
        setStatus('전송 중\u2026');
      } else if (phase === 'success') {
        setStatus('전송 완료', 'saved'); // matches pairingMsg '전송 완료 ✓'
      }
      // waiting: no status change — preserve current recording/save status
    }
    // 'paired' with no error_code: preserve previous pairingError text and status.
    // 'idle' / 'failed' with no error_code: section hides or error already shown.

    updatePairingUI();
  });
}

// G6: After all listeners are registered, hydrate device Map and query
// last recording path; then confirm discovery is running.
setupListeners()
  .then(async () => {
    try {
      const existing = await invoke('list_devices');
      for (const device of existing) {
        devices.set(device.name, device);
      }
      renderDeviceList();
    } catch (err) {
      console.warn('[C2V] list_devices failed:', err);
    }
    try {
      await invoke('start_discovery');
    } catch (err) {
      console.warn('[C2V] start_discovery failed:', err);
    }
    // Hydrate last recording path so send button is correct on startup
    await refreshLastRecordingPath();
    // Hydrate pairing status in case a previous session left us paired
    try {
      const status = await invoke('get_pairing_status');
      currentPairingPhase = status.phase;
      updatePairingUI();
    } catch (_) {}
  })
  .catch((err) => {
    setStatus('기기 목록을 불러오지 못했습니다', 'error');
    console.error('[C2V] Failed to register listeners:', err);
  });

// G5: Release all listeners and stop discovery on page hide/unload
window.addEventListener('pagehide', async () => {
  if (unlistenRecordingError) { unlistenRecordingError(); unlistenRecordingError = null; }
  if (unlistenDeviceFound)    { unlistenDeviceFound();    unlistenDeviceFound = null; }
  if (unlistenDeviceLost)     { unlistenDeviceLost();     unlistenDeviceLost = null; }
  if (unlistenBrowserError)   { unlistenBrowserError();   unlistenBrowserError = null; }
  if (unlistenPairingState)   { unlistenPairingState();   unlistenPairingState = null; }
  try { await invoke('stop_discovery'); } catch (_) {}
});

// ── Recording controls ───────────────────────────────────────────

btnStart.addEventListener('click', async () => {
  btnStart.disabled = true;
  setStatus('마이크를 열고 있습니다\u2026');
  try {
    await invoke('start_recording');
    btnStop.disabled = false;
    startTimer();
    setStatus('녹음 중\u2026');
  } catch (err) {
    btnStart.disabled = false;
    // N1: translate stable code (or fall back to Korean) — never show raw English
    setStatus(getErrorMessage(String(err)), 'error');
  }
});

btnStop.addEventListener('click', async () => {
  btnStop.disabled = true;
  stopTimer();
  setStatus('저장 중\u2026');
  const phaseAtStop = currentPairingPhase; // G1: snapshot before stop_recording (TOCTOU)
  try {
    const savedPath = await invoke('stop_recording');
    timerEl.textContent = formatTime(finalElapsedSec);
    // Never paint the full absolute path (contains username)
    const fileName = fileNameOnly(savedPath);
    setStatus(`저장 완료: ${fileName}`, 'saved');
    // Update send button now that we have a new recording
    await refreshLastRecordingPath();
    // G4: basename gate — no recording means nothing to auto-send
    if ((phaseAtStop === 'paired' || phaseAtStop === 'success') && lastRecordingBasename) {
      pairingError.textContent = '';
      setStatus('전송 중\u2026'); // G5: Korean UX feedback before invoke
      try {
        await invoke('send_last_recording');
      } catch (err) {
        showPairingInvokeError(err); // G2/G3: invoke-reject only; HTTP handled by pairing-state
      }
    }
  } catch (err) {
    // N1: translate stable code (save_timeout etc.) — never show raw English
    setStatus(getErrorMessage(String(err)), 'error');
  } finally {
    btnStart.disabled = false;
  }
});

// ── Pairing controls ─────────────────────────────────────────────

// Shared invoke-reject handler for btnSend and auto-send.
// Only for Rust Err returns (not_paired, no_recording_available, etc.).
// HTTP outcomes (401, 500) arrive via pairing-state event — not handled here.
function showPairingInvokeError(err) {
  pairingError.textContent = getErrorMessage(String(err));
  // SSOT: let updatePairingUI drive section visibility based on current phase.
  // idle-phase fallback: if section is still hidden, force show so error is visible.
  updatePairingUI();
  if (pairingSection.style.display === 'none') {
    pairingSection.style.display = '';
  }
}

btnCancelPair.addEventListener('click', async () => {
  try {
    await invoke('cancel_pairing');
  } catch (err) {
    console.warn('[C2V] cancel_pairing failed:', err);
  }
});

btnSend.addEventListener('click', async () => {
  btnSend.disabled = true; // N-G4: prevent double-tap; restored by updatePairingUI on invoke error
  pairingError.textContent = '';
  try {
    await invoke('send_last_recording');
    // success path: uploading event arrives → updatePairingUI keeps btnSend disabled until result
  } catch (err) {
    showPairingInvokeError(err); // calls updatePairingUI internally → restores btnSend
  }
});
