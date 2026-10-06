//! Automatic Gain Control (AGC) for microphone audio.
//!
//! Tracks speech energy using an asymmetric peak envelope follower, applies
//! smoothed gain to target a consistent speech level, suppresses amplification
//! during silence (noise gating), and prevents digital clipping with a soft limiter.

pub struct Agc {
    envelope: f32,
    gain: f32,
    attack_coef: f32,
    release_coef: f32,
    gain_smooth_coef: f32,
    target_amplitude: f32,
    max_gain: f32,
    min_gain: f32,
    silence_threshold: f32,
}

impl Agc {
    /// Creates a new AGC processor.
    ///
    /// - `sample_rate`: Audio sample rate in Hz (typically 48000.0).
    /// - `target_dbfs`: Target speech peak level relative to 16-bit full scale (e.g. -14.0 dBFS).
    /// - `max_gain_db`: Maximum gain boost for quiet speech (e.g. 18.0 dB).
    pub fn new(sample_rate: f32, target_dbfs: f32, max_gain_db: f32) -> Self {
        const FULL_SCALE: f32 = 32767.0;
        let attack_time = 0.008; // 8 ms attack for speech transients
        let release_time = 0.250; // 250 ms release hold between syllables
        let gain_smooth_time = 0.015; // 15 ms gain smoothing to prevent distortion

        let target_ratio = 10.0f32.powf(target_dbfs / 20.0);
        let max_gain_ratio = 10.0f32.powf(max_gain_db / 20.0);
        // Silence gate at -42 dBFS: avoid amplifying background noise when not speaking
        let silence_ratio = 10.0f32.powf(-42.0 / 20.0);

        Self {
            envelope: 0.0,
            gain: 1.0,
            attack_coef: (-1.0 / (attack_time * sample_rate)).exp(),
            release_coef: (-1.0 / (release_time * sample_rate)).exp(),
            gain_smooth_coef: (-1.0 / (gain_smooth_time * sample_rate)).exp(),
            target_amplitude: FULL_SCALE * target_ratio,
            max_gain: max_gain_ratio,
            min_gain: 0.125, // Down to -18 dB attenuation for loud shouts
            silence_threshold: FULL_SCALE * silence_ratio,
        }
    }

    /// Processes audio samples in-place.
    ///
    /// Samples are 16-bit PCM values in floating point representation ([-32768.0, 32767.0]).
    pub fn process(&mut self, samples: &mut [f32]) {
        for sample in samples.iter_mut() {
            if !sample.is_finite() {
                *sample = 0.0;
                continue;
            }

            let abs_val = sample.abs();

            // Asymmetric peak envelope follower
            if abs_val > self.envelope {
                self.envelope =
                    self.attack_coef * self.envelope + (1.0 - self.attack_coef) * abs_val;
            } else {
                self.envelope =
                    self.release_coef * self.envelope + (1.0 - self.release_coef) * abs_val;
            }

            // Flush tiny denormals
            if self.envelope < 1e-5 {
                self.envelope = 0.0;
            }

            // Calculate target gain
            let target_gain = if self.envelope > self.silence_threshold {
                let required = self.target_amplitude / self.envelope;
                required.clamp(self.min_gain, self.max_gain)
            } else {
                // Return smoothly towards unity gain during silence / noise floor
                1.0
            };

            // Smooth the gain to avoid zipper noise and audio distortion
            self.gain =
                self.gain_smooth_coef * self.gain + (1.0 - self.gain_smooth_coef) * target_gain;

            let amplified = *sample * self.gain;

            // Soft-knee limiter near digital full scale (32767) to eliminate harsh clipping
            *sample = Self::soft_limit(amplified);
        }
    }

    /// Resets internal envelope and gain state.
    pub fn reset(&mut self) {
        self.envelope = 0.0;
        self.gain = 1.0;
    }

    /// Smooth hyperbolic soft limiter for samples exceeding 28000.0 peak.
    #[inline(always)]
    fn soft_limit(x: f32) -> f32 {
        const THRESHOLD: f32 = 28000.0;
        const MARGIN: f32 = 4767.0; // 32767.0 - 28000.0
        let abs_x = x.abs();
        if abs_x <= THRESHOLD {
            x
        } else {
            let over = abs_x - THRESHOLD;
            let compressed = THRESHOLD + MARGIN * (over / (over + MARGIN));
            if x > 0.0 { compressed } else { -compressed }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_amplifies_quiet_speech() {
        // -14 dBFS target, 18 dB max gain
        let mut agc = Agc::new(48000.0, -14.0, 18.0);
        // Quiet speech: peak around 1500 (-26 dBFS), above silence threshold (~260)
        let mut samples = vec![1500.0; 4800]; // 100 ms
        agc.process(&mut samples);

        // After settling, gain should amplify speech toward target (~6500)
        let last_sample = samples[samples.len() - 1];
        assert!(
            last_sample > 3000.0,
            "Quiet speech should be amplified, got {last_sample}"
        );
        assert!(last_sample <= 32767.0, "Should not exceed full scale");
    }

    #[test]
    fn it_compresses_loud_shouts() {
        let mut agc = Agc::new(48000.0, -14.0, 18.0);
        // Very loud speech at 28000 amplitude
        let mut samples = vec![28000.0; 4800];
        agc.process(&mut samples);

        let last_sample = samples[samples.len() - 1];
        assert!(
            last_sample < 28000.0,
            "Loud speech should be attenuated, got {last_sample}"
        );
    }

    #[test]
    fn it_does_not_boost_silence_noise_floor() {
        let mut agc = Agc::new(48000.0, -14.0, 18.0);
        // Low background noise below silence threshold (e.g. 50.0 amplitude)
        let mut samples = vec![50.0; 4800];
        agc.process(&mut samples);

        let last_sample = samples[samples.len() - 1];
        // Since it's below threshold, gain should remain near unity (1.0)
        assert!(
            (last_sample - 50.0).abs() < 5.0,
            "Silence should remain unamplified, got {last_sample}"
        );
    }

    #[test]
    fn it_handles_non_finite_samples() {
        let mut agc = Agc::new(48000.0, -14.0, 18.0);
        let mut samples = vec![f32::NAN, f32::INFINITY, -f32::INFINITY, 1000.0];
        agc.process(&mut samples);

        assert_eq!(samples[0], 0.0);
        assert_eq!(samples[1], 0.0);
        assert_eq!(samples[2], 0.0);
        assert!(samples[3].is_finite());
    }

    #[test]
    fn it_resets_cleanly() {
        let mut agc = Agc::new(48000.0, -14.0, 18.0);
        let mut samples = vec![20000.0; 480];
        agc.process(&mut samples);
        assert!(agc.envelope > 0.0);

        agc.reset();
        assert_eq!(agc.envelope, 0.0);
        assert_eq!(agc.gain, 1.0);
    }
}
