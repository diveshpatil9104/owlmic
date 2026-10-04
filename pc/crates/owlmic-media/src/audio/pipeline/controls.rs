//! Processing switches the phone owns, next to the ones in mod.rs, and the output device's rate.

use super::JitterBuffer;
use std::sync::atomic::Ordering;

impl JitterBuffer {
    /// PC noise reduction on or off.
    pub fn set_ns_enabled(&self, enabled: bool) {
        self.ns_enabled.store(enabled, Ordering::Release);
    }

    pub fn is_ns_enabled(&self) -> bool {
        self.ns_enabled.load(Ordering::Acquire)
    }

    /// The strength the processing uses, in percent.
    pub(super) fn ns_strength(&self) -> u32 {
        if self.is_ns_enabled() { 100 } else { 0 }
    }

    /// The output device's sample rate. The phone's 48 kHz is resampled to it on the way out.
    pub fn set_output_rate(&self, rate: u32) {
        self.output_rate.store(rate.max(1), Ordering::Relaxed);
    }
}
