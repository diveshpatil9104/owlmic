use super::*;

#[test]
fn test_audio_dsp_rnnoise_suppression() {
    let mut dsp = AudioDsp::new();
    let mut samples = vec![1000i16; 480 * 4];

    dsp.process(&mut samples, 100);

    assert_eq!(samples.len(), 480 * 4);
    assert!(samples.iter().any(|&s| s != 0));
}

#[test]
fn test_audio_dsp_zero_strength_passthrough() {
    let mut dsp = AudioDsp::new();
    let mut samples = vec![1234i16; 480 * 2];
    let copy = samples.clone();

    dsp.process(&mut samples, 0);

    assert_eq!(samples, copy);
}

/// Voiced speech stand-in: a ~130 Hz pulse train through two formant resonances, which RNNoise
/// passes through as voice.
fn voice_like(len: usize) -> Vec<i16> {
    let sr = 48_000.0f32;
    let resonator = |f: f32, bw: f32| {
        let r = (-std::f32::consts::PI * bw / sr).exp();
        (
            2.0 * r * (2.0 * std::f32::consts::PI * f / sr).cos(),
            -r * r,
        )
    };
    let ((a1, a2), (b1, b2)) = (resonator(700.0, 110.0), resonator(1200.0, 130.0));
    let (mut phase, mut ya, mut yb) = (0.0f32, [0.0f32; 2], [0.0f32; 2]);
    let mut out: Vec<f32> = (0..len)
        .map(|n| {
            let t = n as f32 / sr;
            phase += (130.0 + 8.0 * (2.0 * std::f32::consts::PI * 5.0 * t).sin()) / sr;
            let pulse = if phase >= 1.0 {
                phase -= 1.0;
                1.0
            } else {
                0.0
            };
            let a = pulse + a1 * ya[0] + a2 * ya[1];
            ya = [a, ya[0]];
            let b = a + b1 * yb[0] + b2 * yb[1];
            yb = [b, yb[0]];
            b
        })
        .collect();
    let peak = out.iter().fold(0.0f32, |m, v| m.max(v.abs()));
    out.iter_mut().for_each(|v| *v = *v / peak * 12_000.0);
    out.iter().map(|&v| v as i16).collect()
}

#[test]
fn test_partial_strength_keeps_the_voice_whole() {
    // RNNoise's output is one frame late. Blending it with the raw audio of the same moment
    // cancels parts of the voice (comb filtering). Lined up, a half-strength blend of a voice
    // RNNoise keeps is just that voice, one frame later.
    const FRAME: usize = 480;
    let input = voice_like(FRAME * 200);
    let mut output = input.clone();
    let mut dsp = AudioDsp::new();
    for chunk in output.chunks_mut(FRAME) {
        dsp.process(chunk, 50);
    }

    let settled = FRAME * 100;
    let (mut err, mut sig) = (0.0f64, 0.0f64);
    for n in settled..input.len() {
        let expected = input[n - FRAME] as f64;
        err += (output[n] as f64 - expected).powi(2);
        sig += expected.powi(2);
    }
    let relative = (err / sig).sqrt();
    assert!(relative < 0.1, "voice changed by {:.0}%", relative * 100.0);
}
