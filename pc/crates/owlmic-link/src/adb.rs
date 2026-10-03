//! USB debugging (SYSTEM_DESIGN section 14.3): when a phone's ADB interface appears, run the
//! bundled adb with `reverse tcp:7653 tcp:7653`, so the phone reaches this PC at 127.0.0.1. The
//! adb server runs only while a phone is plugged in, and only one Owlmic started is stopped.

use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::time::Duration;
use windows::Win32::Devices::DeviceAndDriverInstallation::{
    CM_GET_DEVICE_INTERFACE_LIST_PRESENT, CM_Get_Device_Interface_List_SizeW, CM_NOTIFY_ACTION,
    CM_NOTIFY_ACTION_DEVICEINTERFACEARRIVAL, CM_NOTIFY_ACTION_DEVICEINTERFACEREMOVAL,
    CM_NOTIFY_EVENT_DATA, CM_NOTIFY_FILTER, CM_NOTIFY_FILTER_TYPE_DEVICEINTERFACE,
    CM_Register_Notification, CR_SUCCESS, HCMNOTIFICATION,
};
use windows::core::GUID;

/// The interface Android's ADB function exposes on Windows.
const ADB_INTERFACE: GUID = GUID::from_u128(0xF72FE0D4_CBCB_407D_8814_9ED673D0DD6B);
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const ADB_SERVER_PORT: u16 = 5037;

enum Event {
    Changed,
}

/// Where adb lives: `adb\adb.exe` next to owlmic.exe, or `adb` on the PATH while developing.
pub fn adb_path() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(|d| d.join("adb").join("adb.exe")))
        .filter(|p| p.exists())
        .unwrap_or_else(|| PathBuf::from("adb"))
}

fn adb(args: &[&str]) -> Option<String> {
    let out = Command::new(adb_path())
        .args(args)
        .creation_flags(CREATE_NO_WINDOW)
        .stdin(Stdio::null())
        .output()
        .ok()?;
    Some(String::from_utf8_lossy(&out.stdout).into_owned())
}

fn adb_interfaces_present() -> bool {
    let mut len = 0u32;
    let r = unsafe {
        CM_Get_Device_Interface_List_SizeW(
            &mut len,
            &ADB_INTERFACE,
            None,
            CM_GET_DEVICE_INTERFACE_LIST_PRESENT,
        )
    };
    // An empty list is a single terminating null.
    r == CR_SUCCESS && len > 1
}

fn server_already_running() -> bool {
    std::net::TcpStream::connect_timeout(
        &([127, 0, 0, 1], ADB_SERVER_PORT).into(),
        Duration::from_millis(200),
    )
    .is_ok()
}

/// Authorised phones from `adb devices`.
fn authorised_serials() -> Vec<String> {
    adb(&["devices"])
        .unwrap_or_default()
        .lines()
        .skip(1)
        .filter_map(|l| l.split_once('\t'))
        .filter(|(_, state)| state.trim() == "device")
        .map(|(serial, _)| serial.to_owned())
        .collect()
}

unsafe extern "system" fn on_interface(
    _h: HCMNOTIFICATION,
    context: *const core::ffi::c_void,
    action: CM_NOTIFY_ACTION,
    _data: *const CM_NOTIFY_EVENT_DATA,
    _size: u32,
) -> u32 {
    if action == CM_NOTIFY_ACTION_DEVICEINTERFACEARRIVAL
        || action == CM_NOTIFY_ACTION_DEVICEINTERFACEREMOVAL
    {
        let tx = unsafe { &*(context as *const mpsc::Sender<Event>) };
        let _ = tx.send(Event::Changed);
    }
    0
}

/// Starts watching for phones with USB debugging. Runs for the life of the app.
pub fn start() {
    let (tx, rx) = mpsc::channel();
    // The callback's context lives as long as the app, so it is leaked on purpose.
    let context: &'static mpsc::Sender<Event> = Box::leak(Box::new(tx.clone()));
    let mut filter = CM_NOTIFY_FILTER {
        cbSize: size_of::<CM_NOTIFY_FILTER>() as u32,
        FilterType: CM_NOTIFY_FILTER_TYPE_DEVICEINTERFACE,
        ..Default::default()
    };
    filter.u.DeviceInterface.ClassGuid = ADB_INTERFACE;
    let mut handle = HCMNOTIFICATION::default();
    unsafe {
        CM_Register_Notification(
            &filter,
            Some(context as *const _ as *const _),
            Some(on_interface),
            &mut handle,
        );
    }
    let _ = tx.send(Event::Changed);
    let _ = std::thread::Builder::new()
        .name("owlmic-adb".into())
        .spawn(move || run(rx));
}

fn run(rx: mpsc::Receiver<Event>) {
    let mut started_server = false;
    let mut tracker: Option<Child> = None;
    let mut reversed: Vec<String> = Vec::new();
    loop {
        let present = adb_interfaces_present();
        if present {
            if tracker.is_none() {
                started_server = !server_already_running();
                // track-devices keeps the server up while phones are plugged in.
                tracker = Command::new(adb_path())
                    .arg("track-devices")
                    .creation_flags(CREATE_NO_WINDOW)
                    .stdin(Stdio::null())
                    .stdout(Stdio::piped())
                    .spawn()
                    .ok();
                if let Some(out) = tracker.as_mut().and_then(|t| t.stdout.take()) {
                    drain(out);
                }
            }
            for serial in authorised_serials() {
                if !reversed.contains(&serial)
                    && adb(&["-s", &serial, "reverse", "tcp:7653", "tcp:7653"]).is_some()
                {
                    reversed.push(serial);
                }
            }
        } else if let Some(mut t) = tracker.take() {
            let _ = t.kill();
            let _ = t.wait();
            reversed.clear();
            if started_server {
                let _ = adb(&["kill-server"]);
                started_server = false;
            }
        }
        // Device changes arrive as events; the timeout re-checks phones whose prompt was just allowed.
        match rx.recv_timeout(if present {
            Duration::from_secs(2)
        } else {
            Duration::from_secs(3600)
        }) {
            Ok(Event::Changed) | Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => return,
        }
    }
}

/// Drains adb's output on its own thread so its pipe never fills.
fn drain(mut out: impl std::io::Read + Send + 'static) {
    std::thread::spawn(move || {
        let mut buf = [0u8; 512];
        while matches!(out.read(&mut buf), Ok(n) if n > 0) {}
    });
}
