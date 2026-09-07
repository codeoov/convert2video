#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod audio;
mod discovery;
mod pairing;
mod upload;

use audio::RecorderState;
use discovery::BrowserState;
use pairing::PairingState;
use tauri::Manager;

fn main() {
    tauri::Builder::default()
        .manage(RecorderState::new())
        .manage(BrowserState::new())
        .manage(PairingState::new())
        .setup(|app| {
            let handle = app.handle().clone();
            let state = app.state::<BrowserState>();
            discovery::auto_start(handle, &*state);
            Ok(())
        })
        .on_window_event(|window, event| {
            // Stop mDNS browse when window is destroyed or close is requested,
            // so the daemon and worker thread are torn down cleanly.
            match event {
                tauri::WindowEvent::Destroyed | tauri::WindowEvent::CloseRequested { .. } => {
                    window.state::<BrowserState>().stop_all();
                }
                _ => {}
            }
        })
        .invoke_handler(tauri::generate_handler![
            audio::start_recording,
            audio::stop_recording,
            audio::get_last_recording_path,
            discovery::start_discovery,
            discovery::stop_discovery,
            discovery::list_devices,
            pairing::request_pairing,
            pairing::cancel_pairing,
            pairing::get_pairing_status,
            upload::send_last_recording,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
