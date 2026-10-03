//! Keeps Windows' busy pointer (the arrow with a spinning ring) away: Windows shows it while a
//! desktop program starts, until that program first waits for input.

use std::ffi::c_void;
use std::io;

#[repr(C)]
pub(crate) struct Msg {
    hwnd: usize,
    message: u32,
    wparam: usize,
    lparam: isize,
    time: u32,
    pt: [i32; 2],
}

#[repr(C)]
struct StartupInfoW {
    cb: u32,
    reserved: *mut u16,
    desktop: *mut u16,
    title: *mut u16,
    position_and_size: [u32; 7],
    flags: u32,
    show_window: u16,
    reserved2_len: u16,
    reserved2: *mut u8,
    std_handles: [usize; 3],
}

#[repr(C)]
struct ProcessInformation {
    process: usize,
    thread: usize,
    process_id: u32,
    thread_id: u32,
}

#[link(name = "user32")]
extern "system" {
    pub(crate) fn PeekMessageW(msg: *mut Msg, hwnd: usize, min: u32, max: u32, remove: u32) -> i32;
    fn GetMessageW(msg: *mut Msg, hwnd: usize, min: u32, max: u32) -> i32;
    fn PostThreadMessageW(thread_id: u32, msg: u32, wparam: usize, lparam: isize) -> i32;
}

#[link(name = "kernel32")]
extern "system" {
    fn GetCurrentThreadId() -> u32;
    fn CreateProcessW(
        application_name: *const u16,
        command_line: *mut u16,
        process_attributes: *const c_void,
        thread_attributes: *const c_void,
        inherit_handles: i32,
        creation_flags: u32,
        environment: *const c_void,
        current_directory: *const u16,
        startup_info: *const StartupInfoW,
        process_information: *mut ProcessInformation,
    ) -> i32;
    fn CloseHandle(handle: usize) -> i32;
}

/// Ends Owlmic's own busy pointer at once, instead of after startup. An empty PeekMessage is
/// where Windows counts a program as waiting for input, and the docs name the first GetMessage,
/// so this does both; a message is posted first so GetMessage never blocks.
pub fn end_busy_pointer() {
    const PM_NOREMOVE: u32 = 0;
    const WM_NULL: u32 = 0;
    unsafe {
        let mut msg: Msg = std::mem::zeroed();
        PeekMessageW(&mut msg, 0, 0, 0, PM_NOREMOVE);
        if PostThreadMessageW(GetCurrentThreadId(), WM_NULL, 0, 0) != 0 {
            GetMessageW(&mut msg, 0, 0, 0);
        }
    }
}

/// Starts a program, like Explorer on a folder, without the busy pointer.
pub fn start(program: &str, args: &[&str]) -> io::Result<()> {
    const STARTF_FORCEOFFFEEDBACK: u32 = 0x80;
    let mut line: Vec<u16> = command_line(program, args)
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    unsafe {
        let mut startup: StartupInfoW = std::mem::zeroed();
        startup.cb = std::mem::size_of::<StartupInfoW>() as u32;
        startup.flags = STARTF_FORCEOFFFEEDBACK;
        let mut info: ProcessInformation = std::mem::zeroed();
        let ok = CreateProcessW(
            std::ptr::null(),
            line.as_mut_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            0,
            0,
            std::ptr::null(),
            std::ptr::null(),
            &startup,
            &mut info,
        );
        if ok == 0 {
            return Err(io::Error::last_os_error());
        }
        CloseHandle(info.process);
        CloseHandle(info.thread);
    }
    Ok(())
}

/// Quotes every part the way Windows programs split their command line: backslashes are only
/// special before a quote, so those get doubled.
fn command_line(program: &str, args: &[&str]) -> String {
    let mut line = String::new();
    for part in std::iter::once(program).chain(args.iter().copied()) {
        if !line.is_empty() {
            line.push(' ');
        }
        line.push('"');
        let mut backslashes = 0;
        for c in part.chars() {
            if c == '\\' {
                backslashes += 1;
            } else {
                if c == '"' {
                    line.extend(std::iter::repeat_n('\\', backslashes + 1));
                }
                backslashes = 0;
            }
            line.push(c);
        }
        line.extend(std::iter::repeat_n('\\', backslashes));
        line.push('"');
    }
    line
}

#[cfg(test)]
mod tests {
    use super::command_line;

    #[test]
    fn test_command_line_quoting() {
        assert_eq!(
            command_line("explorer.exe", &[r"C:\Users\A B\AppData\Owlmic\logs"]),
            r#""explorer.exe" "C:\Users\A B\AppData\Owlmic\logs""#
        );
        // A trailing backslash would otherwise escape the closing quote
        assert_eq!(command_line("x", &[r"C:\dir\"]), r#""x" "C:\dir\\""#);
        assert_eq!(command_line("x", &[r#"say "hi""#]), r#""x" "say \"hi\"""#);
        assert_eq!(command_line("x", &[r#"a\"b"#]), r#""x" "a\\\"b""#);
    }
}
