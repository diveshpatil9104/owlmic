#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let has = |flag: &str| args.iter().any(|a| a == flag);
    // The installer runs these elevated, possibly while another Owlmic runs: no single-instance
    // check and no tray.
    if has("--register-camera") {
        std::process::exit(if owlmic_app::camera::register() { 0 } else { 1 });
    }
    if has("--unregister-camera") {
        std::process::exit(if owlmic_app::camera::unregister() {
            0
        } else {
            1
        });
    }
    if owlmic_app::instance::already_running() {
        owlmic_ui::win::open_running();
        return;
    }
    owlmic_app::start::run(!has("--autostart"));
}

#[cfg(not(windows))]
fn main() {}
