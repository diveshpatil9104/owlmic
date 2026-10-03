mod constants;
mod controls;
mod resample;
#[cfg(test)]
mod tests;

pub use constants::*;

use crate::audio::dsp::AudioDsp;
use resample::{JitterStats, Playout};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::Instant;

pub struct JitterBuffer {
    buffer: Mutex<VecDeque<i16>>,
    base_target_samples: AtomicUsize,
    adaptive_target_samples: AtomicUsize,
    peak_level: AtomicUsize,
    ns_strength: AtomicUsize,
    ns_enabled: AtomicBool,
    dsp: Mutex<AudioDsp>,
    stats: Mutex<JitterStats>,
    playout: Mutex<Playout>,
    output_rate: AtomicU32,
}

impl JitterBuffer {
    pub fn new() -> Self {
        let base_target = USB_TARGET_MS * SAMPLES_PER_MS;
        Self {
            buffer: Mutex::new(VecDeque::with_capacity(MAX_SAMPLES)),
            base_target_samples: AtomicUsize::new(base_target),
            adaptive_target_samples: AtomicUsize::new(base_target),
            peak_level: AtomicUsize::new(0),
            ns_strength: AtomicUsize::new(100),
            ns_enabled: AtomicBool::new(true),
            dsp: Mutex::new(AudioDsp::new()),
            stats: Mutex::new(JitterStats::new()),
            playout: Mutex::new(Playout::new()),
            output_rate: AtomicU32::new(SAMPLE_RATE),
        }
    }

    pub fn set_ns_strength(&self, pct: u32) {
        self.ns_strength
            .store(pct.min(100) as usize, Ordering::Release);
    }

    pub fn get_ns_strength(&self) -> u32 {
        self.ns_strength.load(Ordering::Acquire) as u32
    }

    pub fn set_level(&self, level: u8) {
        let target_ms = match level {
            1 | 2 => USB_TARGET_MS,
            3 => WIFI_TARGET_MS,
            4 => BT_TARGET_MS,
            _ => USB_TARGET_MS,
        };
        let samples = target_ms * SAMPLES_PER_MS;
        self.base_target_samples.store(samples, Ordering::Relaxed);
        self.adaptive_target_samples
            .store(samples, Ordering::Relaxed);
    }

    pub fn record_arrival(&self, capture_ts: u64) {
        let now = Instant::now();
        if let Ok(mut stats) = self.stats.lock() {
            if let (Some(last_arr), Some(last_cap)) = (stats.last_arrival, stats.last_capture_ts) {
                let delta_arrival_us = now.duration_since(last_arr).as_micros() as f64;
                let delta_capture_us = capture_ts.saturating_sub(last_cap) as f64;
                let d = (delta_arrival_us - delta_capture_us).abs();
                stats.jitter_estimate_us += (d - stats.jitter_estimate_us) / 16.0;

                let jitter_ms = (stats.jitter_estimate_us / 1000.0) as usize;
                let base = self.base_target_samples.load(Ordering::Relaxed);
                let adaptive_ms = (base / SAMPLES_PER_MS) + (jitter_ms * 2);
                let clamped_ms = adaptive_ms.min(MAX_ADAPTIVE_TARGET_MS);
                self.adaptive_target_samples
                    .store(clamped_ms * SAMPLES_PER_MS, Ordering::Relaxed);
            }
            stats.last_arrival = Some(now);
            stats.last_capture_ts = Some(capture_ts);
        }
    }

    pub fn push_samples(&self, samples: &[i16]) {
        if samples.is_empty() {
            return;
        }

        let mut processed = samples.to_vec();
        if let Ok(mut dsp) = self.dsp.lock() {
            let ns = self.effective_ns_strength();
            dsp.process(&mut processed, ns);
        }

        resample::update_peak_level(&self.peak_level, &processed);

        if let Ok(mut buf) = self.buffer.lock() {
            buf.extend(processed.iter().copied());
            if buf.len() > MAX_SAMPLES {
                let excess = buf.len() - MAX_SAMPLES;
                buf.drain(0..excess);
            }
        }
    }

    pub fn reset(&self) {
        if let Ok(mut buf) = self.buffer.lock() {
            buf.clear();
        }
        if let Ok(mut playout) = self.playout.lock() {
            *playout = Playout::new();
        }
        if let Ok(mut dsp) = self.dsp.lock() {
            dsp.reset();
        }
    }

    pub fn len(&self) -> usize {
        self.buffer.lock().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.buffer.lock().unwrap().is_empty()
    }

    pub fn target_samples(&self) -> usize {
        self.adaptive_target_samples.load(Ordering::Relaxed)
    }

    pub fn get_peak_level(&self) -> f32 {
        let cur = self.peak_level.load(Ordering::Relaxed);
        let decayed = (cur * 85) / 100;
        self.peak_level.store(decayed, Ordering::Relaxed);
        (cur as f32 / 32767.0).clamp(0.0, 1.0)
    }
}

impl Default for JitterBuffer {
    fn default() -> Self {
        Self::new()
    }
}
