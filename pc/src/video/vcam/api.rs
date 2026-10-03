//! Dynamic C-ABI loader for softcam virtual camera library.

use std::ffi::c_void;

pub type FnCreateCamera =
    unsafe extern "C" fn(width: i32, height: i32, framerate: f32) -> *mut c_void;
pub type FnSendFrame = unsafe extern "C" fn(camera: *mut c_void, frame: *const u8);
pub type FnDeleteCamera = unsafe extern "C" fn(camera: *mut c_void);
pub type FnIsConnected = unsafe extern "C" fn(camera: *mut c_void) -> bool;

pub struct SoftcamApi {
    _dll: usize,
    pub create_camera: FnCreateCamera,
    pub send_frame: FnSendFrame,
    pub delete_camera: FnDeleteCamera,
    pub is_connected: FnIsConnected,
}

impl SoftcamApi {
    pub fn load() -> Option<Self> {
        #[link(name = "kernel32")]
        extern "system" {
            fn LoadLibraryW(lpLibFileName: *const u16) -> usize;
            fn GetProcAddress(hModule: usize, lpProcName: *const std::ffi::c_char)
                -> *const c_void;
            fn FreeLibrary(hModule: usize) -> i32;
        }

        let target_path = super::install::ensure_softcam_installed()?;
        super::install::ensure_directshow_registered(&target_path);

        let dll_name: Vec<u16> = target_path
            .to_string_lossy()
            .encode_utf16()
            .chain(std::iter::once(0))
            .collect();

        let h_module = unsafe { LoadLibraryW(dll_name.as_ptr()) };
        if h_module == 0 {
            return None;
        }

        unsafe {
            let p_create = GetProcAddress(h_module, c"scCreateCamera".as_ptr());
            let p_send = GetProcAddress(h_module, c"scSendFrame".as_ptr());
            let p_delete = GetProcAddress(h_module, c"scDeleteCamera".as_ptr());
            let p_connected = GetProcAddress(h_module, c"scIsConnected".as_ptr());

            if p_create.is_null() || p_send.is_null() || p_delete.is_null() || p_connected.is_null()
            {
                FreeLibrary(h_module);
                return None;
            }

            Some(Self {
                _dll: h_module,
                create_camera: std::mem::transmute::<*const c_void, FnCreateCamera>(p_create),
                send_frame: std::mem::transmute::<*const c_void, FnSendFrame>(p_send),
                delete_camera: std::mem::transmute::<*const c_void, FnDeleteCamera>(p_delete),
                is_connected: std::mem::transmute::<*const c_void, FnIsConnected>(p_connected),
            })
        }
    }
}

#[cfg(test)]
impl SoftcamApi {
    /// Stand-ins for softcam.dll's functions, for tests.
    pub fn fake(
        create_camera: FnCreateCamera,
        send_frame: FnSendFrame,
        delete_camera: FnDeleteCamera,
        is_connected: FnIsConnected,
    ) -> Self {
        Self {
            _dll: 0,
            create_camera,
            send_frame,
            delete_camera,
            is_connected,
        }
    }
}
