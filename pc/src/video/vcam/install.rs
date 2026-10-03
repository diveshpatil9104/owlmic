//! Automatic per-user and system-wide DirectShow virtual camera installation and registry management.

use std::ffi::c_void;
use std::path::{Path, PathBuf};

static EMBEDDED_SOFTCAM_DLL: &[u8] = include_bytes!("../../../softcam.dll");

const HKEY_CLASSES_ROOT: usize = 0xFFFF_FFFF_8000_0000;
const HKEY_CURRENT_USER: usize = 0xFFFF_FFFF_8000_0001;
const HKEY_LOCAL_MACHINE: usize = 0xFFFF_FFFF_8000_0002;

const SOFTCAM_CLSID: &str = "{AEF3B972-5FA5-4647-9571-358EB472BC9E}";
const DSHOW_CATEGORY_CLSID: &str = "{860BB310-5D01-11D0-BD3B-00A0C911CE86}";

#[link(name = "advapi32")]
extern "system" {
    fn RegCreateKeyExW(
        hKey: usize,
        lpSubKey: *const u16,
        reserved: u32,
        lpClass: *mut u16,
        dwOptions: u32,
        samDesired: u32,
        lpSecurityAttributes: *mut c_void,
        phkResult: *mut usize,
        lpdwDisposition: *mut u32,
    ) -> i32;
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
    fn RegSetValueExW(
        hKey: usize,
        lpValueName: *const u16,
        reserved: u32,
        dwType: u32,
        lpData: *const u8,
        cbData: u32,
    ) -> i32;
    fn RegOverridePredefKey(hKey: usize, hNewHKey: usize) -> i32;
    fn RegCloseKey(hKey: usize) -> i32;
}

#[link(name = "kernel32")]
extern "system" {
    fn LoadLibraryW(lpLibFileName: *const u16) -> usize;
    fn GetProcAddress(hModule: usize, lpProcName: *const std::ffi::c_char) -> *const c_void;
    fn FreeLibrary(hModule: usize) -> i32;
}

pub fn format_hresult(hr: i32) -> String {
    format!("0x{:08X}", hr as u32)
}

/// Checks whether the current process has write access to HKLM\Software\Classes.
pub fn is_admin() -> bool {
    let subkey: Vec<u16> = "Software\\Classes\0".encode_utf16().collect();
    let mut h = 0usize;
    let res = unsafe {
        RegOpenKeyExW(
            HKEY_LOCAL_MACHINE,
            subkey.as_ptr(),
            0,
            0x20006, // KEY_WRITE
            &mut h,
        )
    };
    if res == 0 {
        unsafe { RegCloseKey(h) };
        true
    } else {
        false
    }
}

pub fn ensure_softcam_installed() -> Option<PathBuf> {
    if let Ok(exe_path) = std::env::current_exe() {
        if let Some(dir) = exe_path.parent() {
            let next_to_exe = dir.join("softcam.dll");
            if next_to_exe.exists() {
                return Some(next_to_exe);
            }
        }
    }

    let local_app_data = std::env::var_os("LOCALAPPDATA")?;
    let app_dir = PathBuf::from(local_app_data).join("Owlmic");
    let target_dll = app_dir.join("softcam.dll");

    let needs_write = match std::fs::metadata(&target_dll) {
        Ok(meta) => meta.len() != EMBEDDED_SOFTCAM_DLL.len() as u64,
        Err(_) => true,
    };

    if needs_write {
        let _ = std::fs::create_dir_all(&app_dir);
        if let Err(e) = std::fs::write(&target_dll, EMBEDDED_SOFTCAM_DLL) {
            eprintln!(
                "[vcam] Failed to extract softcam.dll to {:?}: {}",
                target_dll, e
            );
            return None;
        }
    }

    Some(target_dll)
}

/// Queries the InprocServer32 default value for the softcam CLSID under the specified root key.
pub fn get_registered_inproc_server(root: usize) -> Option<PathBuf> {
    let inproc_path = format!(
        "Software\\Classes\\CLSID\\{}\\InprocServer32\0",
        SOFTCAM_CLSID
    );
    let subkey: Vec<u16> = inproc_path.encode_utf16().collect();
    let mut h_key = 0usize;

    let open_res = unsafe {
        RegOpenKeyExW(
            root,
            subkey.as_ptr(),
            0,
            0x20019, // KEY_READ
            &mut h_key,
        )
    };
    if open_res != 0 {
        return None;
    }

    let mut buf = [0u16; 512];
    let mut size = (buf.len() * 2) as u32;
    let mut val_type = 0u32;
    let query_res = unsafe {
        RegQueryValueExW(
            h_key,
            std::ptr::null(), // default value
            std::ptr::null_mut(),
            &mut val_type,
            buf.as_mut_ptr() as *mut u8,
            &mut size,
        )
    };
    unsafe { RegCloseKey(h_key) };

    if query_res == 0 && (val_type == 1 || val_type == 2) {
        let len = (size as usize / 2).saturating_sub(1);
        let path_str = String::from_utf16_lossy(&buf[..len]);
        let clean = path_str.trim_matches('\0').trim();
        if !clean.is_empty() {
            return Some(PathBuf::from(clean));
        }
    }
    None
}

/// Checks whether DirectShow has registered the instance subkey under the given root key.
pub fn is_instance_registered(root: usize) -> bool {
    let instance_path = format!(
        "Software\\Classes\\CLSID\\{}\\Instance\\DirectShow Softcam\0",
        DSHOW_CATEGORY_CLSID
    );
    let subkey: Vec<u16> = instance_path.encode_utf16().collect();
    let mut h_check = 0usize;

    let open_res = unsafe {
        RegOpenKeyExW(
            root,
            subkey.as_ptr(),
            0,
            0x20019, // KEY_READ
            &mut h_check,
        )
    };
    if open_res == 0 {
        unsafe { RegCloseKey(h_check) };
        true
    } else {
        false
    }
}

/// Sets the DirectShow FriendlyName to "Owlmic Cam" for the virtual camera.
fn set_friendly_name(root: usize) {
    let instance_path = format!(
        "Software\\Classes\\CLSID\\{}\\Instance\\DirectShow Softcam\0",
        DSHOW_CATEGORY_CLSID
    );
    let subkey: Vec<u16> = instance_path.encode_utf16().collect();
    let mut h_instance = 0usize;
    let open_res = unsafe {
        RegOpenKeyExW(
            root,
            subkey.as_ptr(),
            0,
            0x20006, // KEY_WRITE
            &mut h_instance,
        )
    };
    if open_res == 0 {
        let friendly_name: Vec<u16> = "Owlmic Cam\0".encode_utf16().collect();
        let friendly_val_name: Vec<u16> = "FriendlyName\0".encode_utf16().collect();
        unsafe {
            RegSetValueExW(
                h_instance,
                friendly_val_name.as_ptr(),
                0,
                1, // REG_SZ
                friendly_name.as_ptr() as *const u8,
                (friendly_name.len() * 2) as u32,
            );
            RegCloseKey(h_instance);
        }
    }
}

pub fn ensure_directshow_registered(dll_path: &Path) {
    let admin = is_admin();
    let primary_root = if admin {
        HKEY_LOCAL_MACHINE
    } else {
        HKEY_CURRENT_USER
    };

    // Fast path: verify InprocServer32 points to valid active DLL and Instance key exists
    let mut already_valid = false;
    if let Some(registered_path) = get_registered_inproc_server(primary_root) {
        if registered_path == dll_path
            && registered_path.exists()
            && is_instance_registered(primary_root)
        {
            already_valid = true;
        }
    }

    if already_valid {
        set_friendly_name(primary_root);
        return;
    }

    let mut hkcu_classes = 0usize;
    if !admin {
        let classes_subkey: Vec<u16> = "Software\\Classes\0".encode_utf16().collect();
        let create_res = unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                classes_subkey.as_ptr(),
                0,
                std::ptr::null_mut(),
                0,
                0x2001F,
                std::ptr::null_mut(),
                &mut hkcu_classes,
                std::ptr::null_mut(),
            )
        };
        if create_res == 0 {
            unsafe {
                RegOverridePredefKey(HKEY_CLASSES_ROOT, hkcu_classes);
            }
        }
    }

    let wide_dll_path: Vec<u16> = dll_path
        .to_string_lossy()
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();

    let h_mod = unsafe { LoadLibraryW(wide_dll_path.as_ptr()) };
    if h_mod != 0 {
        unsafe {
            let p_reg = GetProcAddress(h_mod, c"DllRegisterServer".as_ptr());
            if !p_reg.is_null() {
                type FnDllRegisterServer = unsafe extern "system" fn() -> i32;
                let reg_fn: FnDllRegisterServer = std::mem::transmute(p_reg);
                let hr = reg_fn();
                if hr != 0 {
                    eprintln!("[vcam] DllRegisterServer failed: {}", format_hresult(hr));
                }
            }
            FreeLibrary(h_mod);
        }
    } else {
        eprintln!("[vcam] Failed to load softcam.dll from {:?}", dll_path);
    }

    if !admin && hkcu_classes != 0 {
        unsafe {
            RegOverridePredefKey(HKEY_CLASSES_ROOT, 0);
            RegCloseKey(hkcu_classes);
        }
    }

    // Set FriendlyName on registered roots
    if admin {
        set_friendly_name(HKEY_LOCAL_MACHINE);
    }
    set_friendly_name(HKEY_CURRENT_USER);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hresult_error_formatting() {
        assert_eq!(format_hresult(0), "0x00000000");
        assert_eq!(format_hresult(0x8007007E_u32 as i32), "0x8007007E");
        assert_eq!(format_hresult(0x80004005_u32 as i32), "0x80004005");
    }

    #[test]
    fn test_is_admin_check_does_not_panic() {
        let _ = is_admin();
    }
}
