//! The Media Hub (SYSTEM_DESIGN section 17): turns the phone's stream messages and the panel's
//! pause tiles into receiver settings and device commands. Media itself never passes through
//! here; the receivers and the sender sit on the carrier and device threads.

use crate::audio::mic::{MicCodec, MicReceiver};
use crate::audio::pipeline::JitterBuffer;
use crate::audio::speaker::{CHANNELS, SpeakerCodec, SpeakerSender};
use crate::video::Framing;
use crate::video::receiver::VideoReceiver;
use owlmic_hub::Hub;
use owlmic_proto::messages::{FeatureState, Message, State, StreamRef, StreamStart};
use std::sync::Arc;

pub const STREAM_MIC: u8 = 1;
pub const STREAM_CAMERA: u8 = 2;
pub const STREAM_SPEAKER: u8 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Feature {
    Mic,
    Camera,
    Speaker,
}

pub enum MediaMsg {
    /// A session went live on, or moved to, this link.
    Link(u8),
    /// The session ended: everything stops.
    Ended,
    FromPhone(Message),
    Setting {
        id: String,
        value: String,
    },
    /// The panel's tiles pause and resume what the phone started (SYSTEM_DESIGN section 17.4).
    Pause {
        feature: Feature,
        paused: bool,
    },
}

#[derive(Debug, Clone, PartialEq)]
pub enum MediaEvent {
    Mic(bool),
    Camera(bool),
    Speaker(bool),
    Shape { framing: Framing, mirror: bool },
    QuietPc(bool),
    ToPhone(Message),
    View(State),
}

pub struct Media {
    pub jitter: Arc<JitterBuffer>,
    pub mic: Arc<MicReceiver>,
    pub speaker: Arc<SpeakerSender>,
    pub video: Arc<VideoReceiver>,
}

pub struct MediaHub {
    media: Media,
    emit: Box<dyn Fn(MediaEvent) + Send>,
    link: Option<u8>,
    /// What the phone has on, from its STATE.
    phone: State,
    /// Paused from the panel.
    paused: [bool; 3],
    mic_codec: Option<MicCodec>,
    camera_on: bool,
    speaker_codec: Option<SpeakerCodec>,
    noise: String,
    phone_has_ns: bool,
    framing: Framing,
    mirror: bool,
    view: State,
}

impl MediaHub {
    pub fn new(media: Media, emit: impl Fn(MediaEvent) + Send + 'static) -> Self {
        Self {
            media,
            emit: Box::new(emit),
            link: None,
            phone: State::default(),
            paused: [false; 3],
            mic_codec: None,
            camera_on: false,
            speaker_codec: None,
            noise: "phone".into(),
            phone_has_ns: true,
            framing: Framing::Fill,
            mirror: false,
            view: State::default(),
        }
    }

    fn is_paused(&self, f: Feature) -> bool {
        self.paused[f as usize]
    }

    /// PC noise reduction (SYSTEM_DESIGN section 17.1): with "Phone + PC", or when the phone
    /// has no noise suppressor of its own.
    fn apply_noise(&self) {
        let on = match self.noise.as_str() {
            "phoneAndPc" => true,
            "off" => false,
            _ => !self.phone_has_ns,
        };
        self.media.jitter.set_ns_enabled(on);
    }

    fn start_mic(&self) {
        if let (Some(codec), Some(link)) = (self.mic_codec, self.link)
            && !self.is_paused(Feature::Mic)
        {
            self.media.mic.start(codec, link);
            (self.emit)(MediaEvent::Mic(true));
        }
    }

    fn stop_mic(&self) {
        self.media.mic.stop();
        (self.emit)(MediaEvent::Mic(false));
    }

    /// Speaker runs when the phone has it on, the panel hasn't paused it, and a link is up. A
    /// new link may need another codec, which restarts the stream.
    fn update_speaker(&mut self) {
        let want = (self.phone.speaker == FeatureState::On && !self.is_paused(Feature::Speaker))
            .then_some(())
            .and(self.link)
            .map(SpeakerCodec::for_link);
        if want == self.speaker_codec {
            return;
        }
        if self.speaker_codec.is_some() && want.is_none() {
            self.media.speaker.stop();
            (self.emit)(MediaEvent::Speaker(false));
            (self.emit)(MediaEvent::ToPhone(Message::StreamStop(StreamRef {
                stream: STREAM_SPEAKER,
            })));
        }
        if let Some(codec) = want {
            self.media.speaker.start(codec);
            let mut params = serde_json::Map::new();
            params.insert("sampleRate".into(), 48_000.into());
            params.insert("channels".into(), (CHANNELS as u64).into());
            params.insert("frameMs".into(), codec.frame_ms().into());
            (self.emit)(MediaEvent::ToPhone(Message::StreamStart(StreamStart {
                stream: STREAM_SPEAKER,
                codec: codec.name().into(),
                params,
            })));
            if self.speaker_codec.is_none() {
                (self.emit)(MediaEvent::Speaker(true));
            }
        }
        self.speaker_codec = want;
    }

    fn stream_start(&mut self, s: StreamStart) {
        match s.stream {
            STREAM_MIC => {
                self.mic_codec = Some(if s.codec == "opus" {
                    MicCodec::Opus
                } else {
                    MicCodec::Pcm
                });
                self.phone_has_ns = s
                    .params
                    .get("noiseSuppressor")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(true);
                self.apply_noise();
                self.start_mic();
            }
            STREAM_CAMERA => {
                self.camera_on = true;
                if !self.is_paused(Feature::Camera) {
                    (self.emit)(MediaEvent::Camera(true));
                }
            }
            _ => {}
        }
    }

    fn stream_stop(&mut self, stream: u8) {
        match stream {
            STREAM_MIC if self.mic_codec.take().is_some() => self.stop_mic(),
            STREAM_CAMERA if self.camera_on => {
                self.camera_on = false;
                (self.emit)(MediaEvent::Camera(false));
            }
            _ => {}
        }
    }

    fn pause(&mut self, feature: Feature, paused: bool) {
        if self.is_paused(feature) == paused {
            return;
        }
        self.paused[feature as usize] = paused;
        match feature {
            Feature::Mic if self.mic_codec.is_some() => {
                if paused {
                    self.stop_mic();
                } else {
                    self.start_mic();
                }
            }
            Feature::Camera if self.camera_on => (self.emit)(MediaEvent::Camera(!paused)),
            Feature::Speaker => self.update_speaker(),
            _ => {}
        }
        let shown = self.shown();
        (self.emit)(MediaEvent::ToPhone(Message::State(shown)));
    }

    fn end(&mut self) {
        self.link = None;
        self.phone = State::default();
        self.paused = [false; 3];
        if self.mic_codec.take().is_some() {
            self.stop_mic();
        }
        if std::mem::take(&mut self.camera_on) {
            (self.emit)(MediaEvent::Camera(false));
        }
        self.update_speaker();
    }

    /// Each feature as the panel shows it: what the phone has on, minus what is paused.
    fn shown(&self) -> State {
        let one = |phone: FeatureState, f: Feature| match phone {
            FeatureState::On if self.is_paused(f) => FeatureState::Paused,
            s => s,
        };
        State {
            mic: one(self.phone.mic, Feature::Mic),
            camera: one(self.phone.camera, Feature::Camera),
            speaker: one(self.phone.speaker, Feature::Speaker),
        }
    }
}

impl Hub for MediaHub {
    type Msg = MediaMsg;

    fn handle(&mut self, msg: MediaMsg) {
        match msg {
            MediaMsg::Link(link) => {
                self.link = Some(link);
                self.media.mic.set_link(link);
                self.update_speaker();
            }
            MediaMsg::Ended => self.end(),
            MediaMsg::FromPhone(Message::StreamStart(s)) => self.stream_start(s),
            MediaMsg::FromPhone(Message::StreamStop(s)) => self.stream_stop(s.stream),
            MediaMsg::FromPhone(Message::State(s)) => {
                // A feature turned off, or resumed on the phone (paused to on), drops the
                // panel's pause of it.
                let before = self.phone;
                for (f, was, now) in [
                    (Feature::Mic, before.mic, s.mic),
                    (Feature::Camera, before.camera, s.camera),
                    (Feature::Speaker, before.speaker, s.speaker),
                ] {
                    if (now == FeatureState::Off
                        || (was == FeatureState::Paused && now == FeatureState::On))
                        && std::mem::take(&mut self.paused[f as usize])
                        && now == FeatureState::On
                    {
                        match f {
                            Feature::Mic => self.start_mic(),
                            Feature::Camera if self.camera_on => {
                                (self.emit)(MediaEvent::Camera(true))
                            }
                            _ => {}
                        }
                    }
                }
                self.phone = s;
                self.update_speaker();
            }
            MediaMsg::FromPhone(Message::RestartStream(s)) if s.stream == STREAM_SPEAKER => {
                self.speaker_codec = None;
                self.media.speaker.stop();
                self.update_speaker();
            }
            MediaMsg::FromPhone(_) => {}
            MediaMsg::Setting { id, value } => match id.as_str() {
                "mic.noiseReduction" => {
                    self.noise = value;
                    self.apply_noise();
                }
                "camera.framing" | "camera.mirror" => {
                    if id == "camera.framing" {
                        self.framing = if value == "fit" {
                            Framing::Fit
                        } else {
                            Framing::Fill
                        };
                    } else {
                        self.mirror = value == "on";
                    }
                    (self.emit)(MediaEvent::Shape {
                        framing: self.framing,
                        mirror: self.mirror,
                    });
                }
                "speaker.quietPc" => (self.emit)(MediaEvent::QuietPc(value == "on")),
                _ => {}
            },
            MediaMsg::Pause { feature, paused } => self.pause(feature, paused),
        }
        let shown = self.shown();
        if shown != self.view {
            self.view = shown;
            (self.emit)(MediaEvent::View(shown));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    fn hub() -> (MediaHub, Arc<Mutex<Vec<MediaEvent>>>) {
        let jitter = Arc::new(JitterBuffer::new());
        let media = Media {
            mic: Arc::new(MicReceiver::new(jitter.clone())),
            jitter,
            speaker: Arc::new(SpeakerSender::new(Box::new(|_, _| {}))),
            video: Arc::new(VideoReceiver::new(|| {})),
        };
        let events = Arc::new(Mutex::new(Vec::new()));
        let e = events.clone();
        (
            MediaHub::new(media, move |ev| e.lock().unwrap().push(ev)),
            events,
        )
    }

    fn take(events: &Mutex<Vec<MediaEvent>>) -> Vec<MediaEvent> {
        std::mem::take(&mut *events.lock().unwrap())
    }

    fn start(stream: u8, codec: &str) -> MediaMsg {
        MediaMsg::FromPhone(Message::StreamStart(StreamStart {
            stream,
            codec: codec.into(),
            params: Default::default(),
        }))
    }

    #[test]
    fn mic_and_camera_follow_the_phone_and_the_panel_can_pause_them() {
        let (mut h, events) = hub();
        h.handle(MediaMsg::Link(3));
        h.handle(start(STREAM_MIC, "opus"));
        h.handle(start(STREAM_CAMERA, "h264"));
        h.handle(MediaMsg::FromPhone(Message::State(State {
            mic: FeatureState::On,
            camera: FeatureState::On,
            speaker: FeatureState::Off,
        })));
        let ev = take(&events);
        assert!(ev.contains(&MediaEvent::Mic(true)) && ev.contains(&MediaEvent::Camera(true)));
        h.handle(MediaMsg::Pause {
            feature: Feature::Mic,
            paused: true,
        });
        let ev = take(&events);
        assert!(ev.contains(&MediaEvent::Mic(false)));
        assert!(ev.iter().any(
            |e| matches!(e, MediaEvent::ToPhone(Message::State(s)) if s.mic == FeatureState::Paused)
        ));
        h.handle(MediaMsg::Ended);
        assert!(take(&events).contains(&MediaEvent::Camera(false)));
    }

    #[test]
    fn speaker_starts_with_the_links_codec_and_restarts_when_the_link_changes() {
        let (mut h, events) = hub();
        h.handle(MediaMsg::Link(1));
        h.handle(MediaMsg::FromPhone(Message::State(State {
            speaker: FeatureState::On,
            ..State::default()
        })));
        let ev = take(&events);
        assert!(ev.contains(&MediaEvent::Speaker(true)));
        assert!(ev.iter().any(|e| matches!(e, MediaEvent::ToPhone(Message::StreamStart(s)) if s.stream == 3 && s.codec == "pcm")));
        h.handle(MediaMsg::Link(3));
        let ev = take(&events);
        assert!(ev.iter().any(
            |e| matches!(e, MediaEvent::ToPhone(Message::StreamStart(s)) if s.codec == "opus")
        ));
        assert!(
            !ev.contains(&MediaEvent::Speaker(false)),
            "a codec change keeps the capture running"
        );
        h.handle(MediaMsg::FromPhone(Message::State(State::default())));
        let ev = take(&events);
        assert!(ev.contains(&MediaEvent::Speaker(false)));
        assert!(
            ev.iter()
                .any(|e| matches!(e, MediaEvent::ToPhone(Message::StreamStop(s)) if s.stream == 3))
        );
    }

    #[test]
    fn pc_noise_reduction_follows_the_setting_and_the_phones_suppressor() {
        let (mut h, _) = hub();
        let mut s = StreamStart {
            stream: STREAM_MIC,
            codec: "pcm".into(),
            params: Default::default(),
        };
        s.params.insert("noiseSuppressor".into(), false.into());
        h.handle(MediaMsg::Link(1));
        h.handle(MediaMsg::FromPhone(Message::StreamStart(s)));
        assert!(
            h.media.jitter.is_ns_enabled(),
            "the phone has none, so the PC reduces noise"
        );
        h.handle(MediaMsg::Setting {
            id: "mic.noiseReduction".into(),
            value: "off".into(),
        });
        assert!(!h.media.jitter.is_ns_enabled());
    }
}
