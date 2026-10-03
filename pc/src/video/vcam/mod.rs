//! Thread-safe Virtual Camera manager for Owlmic.

pub mod api;
pub mod install;

use super::frame::Frame;
use api::SoftcamApi;
use std::ffi::c_void;
use std::sync::{Mutex, OnceLock};

const FPS: f32 = 30.0;

/// Owlmic Cam's only size. Apps like Chrome remember a camera's sizes from when they last listed
/// cameras, which for a DirectShow camera only happens again after a real camera comes or goes,
/// and softcam only serves the size it was created at. A camera that changed size showed nothing
/// in them, so it never changes: every picture is scaled to fit.
pub const WIDTH: usize = 1920;
pub const HEIGHT: usize = 1080;

struct Camera {
    ptr: usize,
    /// The last picture scaled to WIDTH x HEIGHT, reused so scaling allocates nothing.
    scaled: Vec<u8>,
}

/// Thread-safe Virtual Camera manager.
pub struct VirtualCamera {
    api: OnceLock<Option<SoftcamApi>>,
    camera: Mutex<Option<Camera>>,
}

impl Default for VirtualCamera {
    fn default() -> Self {
        Self::new()
    }
}

impl VirtualCamera {
    pub fn new() -> Self {
        Self {
            api: OnceLock::new(),
            camera: Mutex::new(None),
        }
    }

    /// Loads softcam, extracting and registering it on first run, then puts the off frame up so
    /// apps find a working camera before the phone connects. The first run writes files and the
    /// registry, so this runs on a background thread, never on the startup path.
    pub fn load(&self) {
        let api = self.api.get_or_init(|| {
            let api = SoftcamApi::load();
            if api.is_some() {
                println!("[video] Virtual camera backend loaded (softcam.dll found)");
            } else {
                println!(
                    "[video] Virtual camera not installed. Camera preview available; install softcam.dll to use with Zoom/Teams."
                );
            }
            api
        });
        if api.is_some() {
            self.show_off_frame();
        }
    }

    pub fn is_available(&self) -> bool {
        matches!(self.api.get(), Some(Some(_)))
    }

    /// The neutral frame for when the camera is off.
    pub fn show_off_frame(&self) {
        self.push_frame(&Frame::placeholder(WIDTH, HEIGHT));
    }

    /// Sends a frame to the virtual camera. softcam paces sends to FPS, so this can block for up
    /// to a frame.
    pub fn push_frame(&self, frame: &Frame) {
        let Some(Some(api)) = self.api.get() else {
            return;
        };
        let mut guard = self.camera.lock().unwrap();
        if guard.is_none() {
            let ptr = unsafe { (api.create_camera)(WIDTH as i32, HEIGHT as i32, FPS) };
            if ptr.is_null() {
                return;
            }
            *guard = Some(Camera {
                ptr: ptr as usize,
                scaled: Vec::new(),
            });
        }
        let Some(cam) = guard.as_mut() else {
            return;
        };

        let ptr = cam.ptr as *mut c_void;
        if (frame.width, frame.height) == (WIDTH, HEIGHT) {
            unsafe { (api.send_frame)(ptr, frame.bgr.as_ptr()) };
        } else {
            frame.letterbox_into(WIDTH, HEIGHT, &mut cam.scaled);
            unsafe { (api.send_frame)(ptr, cam.scaled.as_ptr()) };
        }
    }
}

impl Drop for VirtualCamera {
    fn drop(&mut self) {
        if let (Some(Some(api)), Ok(mut guard)) = (self.api.get(), self.camera.lock()) {
            if let Some(cam) = guard.take() {
                unsafe { (api.delete_camera)(cam.ptr as *mut c_void) };
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Mutex as StdMutex;

    static CREATED: StdMutex<Vec<(i32, i32)>> = StdMutex::new(Vec::new());
    static SENT: AtomicUsize = AtomicUsize::new(0);
    static DELETED: AtomicUsize = AtomicUsize::new(0);

    unsafe extern "C" fn create(width: i32, height: i32, _fps: f32) -> *mut c_void {
        CREATED.lock().unwrap().push((width, height));
        std::ptr::dangling_mut::<c_void>()
    }
    unsafe extern "C" fn send(_camera: *mut c_void, _frame: *const u8) {
        SENT.fetch_add(1, Ordering::Relaxed);
    }
    unsafe extern "C" fn delete(_camera: *mut c_void) {
        DELETED.fetch_add(1, Ordering::Relaxed);
    }
    unsafe extern "C" fn unused(_camera: *mut c_void) -> bool {
        false
    }

    #[test]
    fn test_camera_keeps_one_size_whatever_the_phone_sends() {
        // Changing size made Owlmic Cam go blank in apps that listed cameras before the change.
        let cam = VirtualCamera::new();
        assert!(cam
            .api
            .set(Some(SoftcamApi::fake(create, send, delete, unused)))
            .is_ok());

        cam.show_off_frame();
        for (w, h) in [
            (1920, 1080),
            (1280, 720),
            (960, 720),
            (1080, 1080),
            (720, 1280),
        ] {
            cam.push_frame(&Frame::new(w, h, vec![128; w * h * 3]));
        }
        cam.show_off_frame();

        assert_eq!(*CREATED.lock().unwrap(), [(WIDTH as i32, HEIGHT as i32)]);
        assert_eq!(DELETED.load(Ordering::Relaxed), 0);
        assert_eq!(SENT.load(Ordering::Relaxed), 7);
    }
}
