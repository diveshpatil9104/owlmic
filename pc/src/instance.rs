//! One Owlmic per user session: a second copy couldn't bind the ports.

use std::ffi::c_void;

#[link(name = "kernel32")]
extern "system" {
    fn CreateMutexW(attributes: *const c_void, initial_owner: i32, name: *const u16) -> usize;
    fn GetLastError() -> u32;
}

const ERROR_ALREADY_EXISTS: u32 = 183;

/// True when Owlmic already runs in this session. The mutex handle is never closed, so it marks
/// this process until it exits.
pub fn already_running() -> bool {
    let name: Vec<u16> = "Local\\OwlmicTray\0".encode_utf16().collect();
    unsafe {
        CreateMutexW(std::ptr::null(), 0, name.as_ptr()) != 0
            && GetLastError() == ERROR_ALREADY_EXISTS
    }
}
