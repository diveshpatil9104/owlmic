use std::env;
use std::io;

const RUN_VALUE: &str = "Owlmic";

pub fn format_autostart_cmd(exe_path: &std::path::Path) -> String {
    format!("\"{}\" --autostart", exe_path.to_string_lossy())
}

fn write_registry_value(val: &str) -> io::Result<()> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;

    #[link(name = "advapi32")]
    extern "system" {
        fn RegOpenKeyExW(
            hKey: usize,
            lpSubKey: *const u16,
            ulOptions: u32,
            samDesired: u32,
            phkResult: *mut usize,
        ) -> i32;
        fn RegSetValueExW(
            hKey: usize,
            lpValueName: *const u16,
            Reserved: u32,
            dwType: u32,
            lpData: *const u8,
            cbData: u32,
        ) -> i32;
        fn RegCloseKey(hKey: usize) -> i32;
    }

    const HKEY_CURRENT_USER: usize = 0x8000_0001;
    const KEY_SET_VALUE: u32 = 0x0002;
    const REG_SZ: u32 = 1;

    let subkey: Vec<u16> = OsStr::new("Software\\Microsoft\\Windows\\CurrentVersion\\Run")
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let value_name: Vec<u16> = OsStr::new(RUN_VALUE)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let wide_path: Vec<u16> = OsStr::new(val)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();

    let mut key_handle = 0usize;
    let res = unsafe {
        RegOpenKeyExW(
            HKEY_CURRENT_USER,
            subkey.as_ptr(),
            0,
            KEY_SET_VALUE,
            &mut key_handle,
        )
    };

    if res != 0 {
        return Err(io::Error::from_raw_os_error(res));
    }

    let ret = unsafe {
        RegSetValueExW(
            key_handle,
            value_name.as_ptr(),
            0,
            REG_SZ,
            wide_path.as_ptr() as *const u8,
            (wide_path.len() * 2) as u32,
        )
    };

    unsafe { RegCloseKey(key_handle) };

    if ret == 0 {
        Ok(())
    } else {
        Err(io::Error::from_raw_os_error(ret))
    }
}

fn delete_registry_value() -> io::Result<()> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;

    #[link(name = "advapi32")]
    extern "system" {
        fn RegOpenKeyExW(
            hKey: usize,
            lpSubKey: *const u16,
            ulOptions: u32,
            samDesired: u32,
            phkResult: *mut usize,
        ) -> i32;
        fn RegDeleteValueW(hKey: usize, lpValueName: *const u16) -> i32;
        fn RegCloseKey(hKey: usize) -> i32;
    }

    const HKEY_CURRENT_USER: usize = 0x8000_0001;
    const KEY_SET_VALUE: u32 = 0x0002;

    let subkey: Vec<u16> = OsStr::new("Software\\Microsoft\\Windows\\CurrentVersion\\Run")
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let value_name: Vec<u16> = OsStr::new(RUN_VALUE)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();

    let mut key_handle = 0usize;
    let res = unsafe {
        RegOpenKeyExW(
            HKEY_CURRENT_USER,
            subkey.as_ptr(),
            0,
            KEY_SET_VALUE,
            &mut key_handle,
        )
    };

    if res != 0 {
        return Err(io::Error::from_raw_os_error(res));
    }

    let ret = unsafe { RegDeleteValueW(key_handle, value_name.as_ptr()) };
    unsafe { RegCloseKey(key_handle) };

    if ret == 0 || ret == 2 {
        // ERROR_FILE_NOT_FOUND (2) on delete is fine
        Ok(())
    } else {
        Err(io::Error::from_raw_os_error(ret))
    }
}

pub fn get_autostart_value() -> Option<String> {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;

    #[link(name = "advapi32")]
    extern "system" {
        fn RegOpenKeyExW(
            hKey: usize,
            lpSubKey: *const u16,
            ulOptions: u32,
            samDesired: u32,
            phkResult: *mut usize,
        ) -> i32;
        fn RegQueryValueExW(
            hKey: usize,
            lpValueName: *const u16,
            lpReserved: *const u32,
            lpType: *mut u32,
            lpData: *mut u8,
            lpcbData: *mut u32,
        ) -> i32;
        fn RegCloseKey(hKey: usize) -> i32;
    }

    const HKEY_CURRENT_USER: usize = 0x8000_0001;
    const KEY_QUERY_VALUE: u32 = 0x0001;
    const REG_SZ: u32 = 1;
    const REG_EXPAND_SZ: u32 = 2;

    let subkey: Vec<u16> = OsStr::new("Software\\Microsoft\\Windows\\CurrentVersion\\Run")
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let value_name: Vec<u16> = OsStr::new(RUN_VALUE)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();

    let mut key_handle = 0usize;
    if unsafe {
        RegOpenKeyExW(
            HKEY_CURRENT_USER,
            subkey.as_ptr(),
            0,
            KEY_QUERY_VALUE,
            &mut key_handle,
        )
    } != 0
    {
        return None;
    }

    let mut buffer = [0u16; 512];
    let mut data_len = (buffer.len() * std::mem::size_of::<u16>()) as u32;
    let mut val_type = 0u32;

    let res = unsafe {
        RegQueryValueExW(
            key_handle,
            value_name.as_ptr(),
            std::ptr::null(),
            &mut val_type,
            buffer.as_mut_ptr() as *mut u8,
            &mut data_len,
        )
    };
    unsafe { RegCloseKey(key_handle) };

    if res != 0 || (val_type != REG_SZ && val_type != REG_EXPAND_SZ) {
        return None;
    }

    let char_len = (data_len as usize) / std::mem::size_of::<u16>();
    let slice = &buffer[..char_len];
    let trimmed = match slice.iter().position(|&c| c == 0) {
        Some(pos) => &slice[..pos],
        None => slice,
    };

    Some(String::from_utf16_lossy(trimmed))
}

pub fn set_autostart(enable: bool) -> io::Result<()> {
    if enable {
        let exe_path = env::current_exe()?;
        write_registry_value(&format_autostart_cmd(&exe_path))
    } else {
        delete_registry_value()
    }
}

pub fn sync_autostart(start_with_computer: bool) -> io::Result<()> {
    let current_val = get_autostart_value();
    if start_with_computer {
        let exe_path = env::current_exe()?;
        let expected_cmd = format_autostart_cmd(&exe_path);
        if let Some(ref val) = current_val {
            if val == &expected_cmd {
                return Ok(());
            }
        }
        write_registry_value(&expected_cmd)
    } else {
        if current_val.is_none() {
            return Ok(());
        }
        delete_registry_value()
    }
}

pub fn is_autostart_enabled() -> bool {
    get_autostart_value().is_some()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;
    use std::sync::Mutex;

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn test_autostart_command_line_formatting() {
        let p = Path::new(r"C:\Program Files\Owlmic\owlmic.exe");
        assert_eq!(
            format_autostart_cmd(p),
            r#""C:\Program Files\Owlmic\owlmic.exe" --autostart"#
        );
    }

    struct RegBackup {
        original: Option<String>,
    }

    impl RegBackup {
        fn new() -> Self {
            Self {
                original: get_autostart_value(),
            }
        }
    }

    impl Drop for RegBackup {
        fn drop(&mut self) {
            match &self.original {
                Some(val) => {
                    let _ = write_registry_value(val);
                }
                None => {
                    let _ = delete_registry_value();
                }
            }
        }
    }

    #[test]
    #[ignore = "changes the real Windows Run key; run with --ignored"]
    fn test_sync_writes_registry_when_missing() {
        let _lock = TEST_LOCK.lock().unwrap();
        let _guard = RegBackup::new();
        let _ = delete_registry_value();
        assert_eq!(get_autostart_value(), None);

        assert!(sync_autostart(true).is_ok());

        let val = get_autostart_value().expect("value should be written");
        assert!(val.ends_with(" --autostart"));
        let current_exe = env::current_exe().unwrap();
        assert!(val.contains(&current_exe.to_string_lossy().to_string()));
    }

    #[test]
    #[ignore = "changes the real Windows Run key; run with --ignored"]
    fn test_sync_removes_registry_when_disabled() {
        let _lock = TEST_LOCK.lock().unwrap();
        let _guard = RegBackup::new();
        let _ = set_autostart(true);
        assert!(get_autostart_value().is_some());

        assert!(sync_autostart(false).is_ok());
        assert_eq!(get_autostart_value(), None);
    }

    #[test]
    #[ignore = "changes the real Windows Run key; run with --ignored"]
    fn test_sync_updates_stale_path() {
        let _lock = TEST_LOCK.lock().unwrap();
        let _guard = RegBackup::new();
        let _ = write_registry_value(r#""C:\Old\Path\owlmic.exe" --autostart"#);
        assert_eq!(
            get_autostart_value(),
            Some(r#""C:\Old\Path\owlmic.exe" --autostart"#.to_string())
        );

        assert!(sync_autostart(true).is_ok());

        let val = get_autostart_value().expect("value should be updated");
        let current_exe = env::current_exe().unwrap();
        assert!(val.contains(&current_exe.to_string_lossy().to_string()));
    }
}
