use nnnoiseless::DenoiseState;

pub struct AudioDsp {
    denoise: Box<DenoiseState<'static>>,
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
            scratch_in: [0.0; DenoiseState::FRAME_SIZE],
            scratch_out: [0.0; DenoiseState::FRAME_SIZE],
            dry: [0.0; DenoiseState::FRAME_SIZE],
        }
    }

    pub fn process(&mut self, samples: &mut [i16], ns_strength_pct: u32) {
        if samples.is_empty() {
            return;
        }

        let strength_ratio = (ns_strength_pct.min(100) as f32) / 100.0;
        if strength_ratio <= 0.001 {
            return;
        }

        for chunk in samples.chunks_mut(DenoiseState::FRAME_SIZE) {
            if chunk.len() < DenoiseState::FRAME_SIZE {
                continue;
            }

            for (dest, &src) in self.scratch_in.iter_mut().zip(chunk.iter()) {
                *dest = src as f32;
            }

            let _vad = self
                .denoise
                .process_frame(&mut self.scratch_out, &self.scratch_in);

            for (dest, (&dry, &denoised)) in chunk
                .iter_mut()
                .zip(self.dry.iter().zip(self.scratch_out.iter()))
            {
                let blended = dry * (1.0 - strength_ratio) + denoised * strength_ratio;
                *dest = blended.clamp(-32768.0, 32767.0) as i16;
            }
            self.dry = self.scratch_in;
        }
    }

    pub fn reset(&mut self) {
        self.scratch_in.fill(0.0);
        self.scratch_out.fill(0.0);
        self.dry.fill(0.0);
    }
}

impl Default for AudioDsp {
    fn default() -> Self {
        Self::new()
    }
}
