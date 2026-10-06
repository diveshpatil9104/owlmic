use super::agc::Agc;
use nnnoiseless::DenoiseState;

pub struct AudioDsp {
    denoise: Box<DenoiseState<'static>>,
    agc: Agc,
    scratch_in: [f32; DenoiseState::FRAME_SIZE],
    scratch_out: [f32; DenoiseState::FRAME_SIZE],
    /// The frame before the current one. RNNoise's output is one frame (10 ms) late, so this is
    /// the raw audio that lines up with it. Mixing in the current frame instead cancels parts of
    /// the voice and makes it sound metallic.
    dry: [f32; DenoiseState::FRAME_SIZE],
}

impl AudioDsp {
    pub fn new() -> Self {
        Self {
            denoise: DenoiseState::new(),
            // 48 kHz, -14 dBFS target speech level, 18 dB max boost
            agc: Agc::new(48000.0, -14.0, 18.0),
            scratch_in: [0.0; DenoiseState::FRAME_SIZE],
            scratch_out: [0.0; DenoiseState::FRAME_SIZE],
            dry: [0.0; DenoiseState::FRAME_SIZE],
        }
    }

    pub fn process(&mut self, samples: &mut [i16], ns_strength_pct: u32, agc: bool) {
        if samples.is_empty() {
            return;
        }

        let strength_ratio = (ns_strength_pct.min(100) as f32) / 100.0;

        for chunk in samples.chunks_mut(DenoiseState::FRAME_SIZE) {
            if chunk.len() < DenoiseState::FRAME_SIZE {
                continue;
            }

            for (dest, &src) in self.scratch_in.iter_mut().zip(chunk.iter()) {
                *dest = src as f32;
            }

            // Only run denoise if strength is high enough
            if strength_ratio > 0.001 {
                let _vad = self
                    .denoise
                    .process_frame(&mut self.scratch_out, &self.scratch_in);

                for (&dry, denoised_ref) in self.dry.iter().zip(self.scratch_out.iter_mut()) {
                    let denoised = *denoised_ref;
                    *denoised_ref = dry * (1.0 - strength_ratio) + denoised * strength_ratio;
                }

                if agc {
                    self.agc.process(&mut self.scratch_out);
                }

                for (dest, &processed) in chunk.iter_mut().zip(self.scratch_out.iter()) {
                    *dest = processed.clamp(-32768.0, 32767.0) as i16;
                }
                self.dry = self.scratch_in;
            } else {
                // If denoise is off, run AGC on scratch_in directly
                if agc {
                    self.agc.process(&mut self.scratch_in);
                }

                for (dest, &processed) in chunk.iter_mut().zip(self.scratch_in.iter()) {
                    *dest = processed.clamp(-32768.0, 32767.0) as i16;
                }
                self.dry = self.scratch_in;
            }
        }
    }

    pub fn reset(&mut self) {
        self.scratch_in.fill(0.0);
        self.scratch_out.fill(0.0);
        self.dry.fill(0.0);
        self.agc.reset();
    }
}

impl Default for AudioDsp {
    fn default() -> Self {
        Self::new()
    }
}
