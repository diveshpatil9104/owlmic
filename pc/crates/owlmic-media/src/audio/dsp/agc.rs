pub struct Agc {
    envelope: f32,
    attack_coef: f32,
    release_coef: f32,
    target_level: f32,
    max_gain: f32,
}

impl Agc {
    pub fn new(sample_rate: f32, target_db: f32, max_gain_db: f32) -> Self {
        let attack_time = 0.005; // 5ms attack
        let release_time = 0.200; // 200ms release
        
        Self {
            envelope: 0.0,
            attack_coef: (-1.0 / (attack_time * sample_rate)).exp(),
            release_coef: (-1.0 / (release_time * sample_rate)).exp(),
            target_level: 10.0f32.powf(target_db / 20.0),
            max_gain: 10.0f32.powf(max_gain_db / 20.0),
        }
    }

    pub fn process(&mut self, samples: &mut [f32]) {
        for sample in samples.iter_mut() {
            let abs_sample = sample.abs();
            
            if abs_sample > self.envelope {
                self.envelope = self.attack_coef * self.envelope + (1.0 - self.attack_coef) * abs_sample;
            } else {
                self.envelope = self.release_coef * self.envelope + (1.0 - self.release_coef) * abs_sample;
            }

            // Prevent division by zero
            let current_level = self.envelope.max(1e-5);
            
            // Calculate required gain to reach target level
            let mut gain = self.target_level / current_level;
            
            // Clamp gain between 1.0 (no reduction if already at target) and max_gain
            // We only want to amplify quiet sounds and limit loud ones, but 
            // since we divide by current_level, if it's high, gain will be < 1.0 (compression)
            // If it's low, gain will be > 1.0 (expansion). We cap the maximum expansion.
            gain = gain.clamp(0.01, self.max_gain);

            *sample *= gain;
        }
    }

    pub fn reset(&mut self) {
        self.envelope = 0.0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_applies_gain() {
        let mut agc = Agc::new(48000.0, -6.0, 20.0);
        let mut samples = vec![0.01; 480]; // Very quiet
        agc.process(&mut samples);
        
        // After processing, the samples should be louder
        assert!(samples[479] > 0.01);
    }
}
