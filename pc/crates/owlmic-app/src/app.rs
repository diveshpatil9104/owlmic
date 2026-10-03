//! The App Hub (SYSTEM_DESIGN section 11.1): routes every hub's events to the hubs that act on
//! them, and publishes one snapshot for the panel and the tray. It holds no media.

use owlmic_devices::DeviceHealth;
use owlmic_hub::{Hub, Outbox, Publisher};
use owlmic_link::{LinkEvent, LinkMsg, LinkView};
use owlmic_media::hub::{Feature as MediaFeature, MediaEvent, MediaMsg};
use owlmic_media::video::Framing;
use owlmic_proto::messages::{FeatureState, Message, Settings, State};
use owlmic_session::hub::{PhaseView, SessionEvent, SessionMsg, SessionView};
use owlmic_session::{hex, id_from_hex};
use owlmic_settings::hub::{SettingsEvent, SettingsMsg, SettingsView};
use owlmic_settings::store::Store;
use owlmic_ui::view::{Action, Connection, Feature, PanelState, PhoneEntry, Repair, Which};
use std::sync::Arc;

pub enum AppMsg {
    Link(LinkEvent),
    Session(SessionEvent),
    Media(MediaEvent),
    Settings(SettingsEvent),
    Health(DeviceHealth),
    Repairing(bool),
    Ui(Action),
    /// This PC's addresses, for typing into the phone.
    Addresses(Vec<String>),
}

/// What the App Hub asks of the Device Hub, kept free of Windows types.
#[derive(Debug, Clone, PartialEq)]
pub enum DeviceCommand {
    Mic(bool),
    Speaker(bool),
    Camera(bool),
    QuietPc(bool),
    Shape { framing: Framing, mirror: bool },
    Repair,
}

pub struct Wiring {
    pub link: Outbox<LinkMsg>,
    pub session: Outbox<SessionMsg>,
    pub media: Outbox<MediaMsg>,
    pub settings: Outbox<SettingsMsg>,
    pub devices: Box<dyn Fn(DeviceCommand) + Send>,
    pub autostart: Box<dyn Fn(bool) + Send>,
    pub quit: Box<dyn Fn() + Send>,
}

#[derive(Default)]
struct AppState {
    session: SessionView,
    link: LinkView,
    /// The link of the last phone that proved itself, for the indicator while it waits.
    proving_link: u8,
    features: State,
    /// Streams the phone should send that have stalled long enough to tell the user.
    restarting: [bool; 4],
    settings: SettingsView,
    health: DeviceHealth,
    repairing: bool,
    addresses: Vec<String>,
}

pub struct AppHub {
    w: Wiring,
    store: Arc<Store>,
    ui: Publisher<PanelState>,
    state: AppState,
    shown: PanelState,
}

/// A setting's next value, for a tile or row that cycles through them.
fn next_value(id: &str, current: &str) -> Option<String> {
    let values = owlmic_settings::find(id)?.values;
    let i = values
        .iter()
        .position(|v| *v == current)
        .map_or(0, |i| (i + 1) % values.len());
    Some(values[i].to_owned())
}

impl AppHub {
    pub fn new(w: Wiring, store: Arc<Store>, ui: Publisher<PanelState>) -> Self {
        let state = AppState {
            proving_link: owlmic_link::LINK_WIFI,
            ..AppState::default()
        };
        Self {
            w,
            store,
            ui,
            state,
            shown: PanelState::default(),
        }
    }

    fn on_link(&mut self, e: LinkEvent) {
        match e {
            LinkEvent::Hello {
                conn,
                phone,
                resume,
            } => self.w.session.send(SessionMsg::Hello {
                conn,
                phone,
                resume,
            }),
            LinkEvent::Proven {
                conn,
                phone,
                decision,
                code,
                link,
            } => {
                self.state.proving_link = link;
                self.w.session.send(SessionMsg::Proven {
                    conn,
                    phone,
                    decision,
                    code,
                    link,
                });
            }
            LinkEvent::ConnClosed { conn } => self.w.session.send(SessionMsg::ConnClosed { conn }),
            LinkEvent::LinkUp { session_id, link } => {
                self.w.session.send(SessionMsg::LinkUp { session_id, link });
                self.w.media.send(MediaMsg::Link(link));
            }
            LinkEvent::LinkLost { session_id } => {
                self.w.session.send(SessionMsg::LinkLost { session_id })
            }
            LinkEvent::Bye { session_id } => self.w.session.send(SessionMsg::Bye { session_id }),
            LinkEvent::FromPhone(Message::Settings(s)) => {
                self.w.settings.send(SettingsMsg::FromPhone(s.changes))
            }
            LinkEvent::FromPhone(
                m @ (Message::State(_)
                | Message::StreamStart(_)
                | Message::StreamStop(_)
                | Message::RestartStream(_)),
            ) => {
                self.w.media.send(MediaMsg::FromPhone(m));
            }
            LinkEvent::FromPhone(_) => {}
            LinkEvent::Stalled { stream, tell_user } => {
                if let Some(r) = self.state.restarting.get_mut(stream as usize) {
                    *r = tell_user;
                }
            }
            LinkEvent::Recovered { stream } => {
                if let Some(r) = self.state.restarting.get_mut(stream as usize) {
                    *r = false;
                }
            }
            LinkEvent::View(v) => self.state.link = v,
        }
    }

    fn on_session(&mut self, e: SessionEvent) {
        match e {
            SessionEvent::Decided { conn, decision } => {
                self.w.link.send(LinkMsg::Decided { conn, decision })
            }
            SessionEvent::Welcome {
                conn,
                session_id,
                phone,
            } => {
                let key = hex(&phone.id);
                let settings = self
                    .store
                    .read(|d| d.pairings.get(&key).cloned())
                    .unwrap_or_default();
                self.w.link.send(LinkMsg::Welcome {
                    conn,
                    session_id,
                    settings,
                });
                self.w.settings.send(SettingsMsg::Session(Some(key)));
            }
            SessionEvent::Rejected { conn, reason } => self.w.link.send(LinkMsg::Reject {
                conn,
                reason,
                owner: None,
            }),
            SessionEvent::Ended { session_id } => {
                self.w.link.send(LinkMsg::SessionEnded { session_id });
                self.w.media.send(MediaMsg::Ended);
                self.w.settings.send(SettingsMsg::Session(None));
                self.state.restarting = [false; 4];
            }
            SessionEvent::Changed(view) => self.state.session = view,
        }
    }

    fn on_media(&mut self, e: MediaEvent) {
        let d = &self.w.devices;
        match e {
            MediaEvent::Mic(on) => d(DeviceCommand::Mic(on)),
            MediaEvent::Camera(on) => d(DeviceCommand::Camera(on)),
            MediaEvent::Speaker(on) => d(DeviceCommand::Speaker(on)),
            MediaEvent::Shape { framing, mirror } => d(DeviceCommand::Shape { framing, mirror }),
            MediaEvent::QuietPc(on) => d(DeviceCommand::QuietPc(on)),
            MediaEvent::ToPhone(m) => self.w.link.send(LinkMsg::Send(m)),
            MediaEvent::View(s) => self.state.features = s,
        }
    }

    fn on_settings(&mut self, e: SettingsEvent) {
        match e {
            SettingsEvent::Apply(values) => {
                for (id, value) in values {
                    if id == "general.startWithWindows" {
                        (self.w.autostart)(value == "on");
                    } else {
                        self.w.media.send(MediaMsg::Setting { id, value });
                    }
                }
            }
            SettingsEvent::ToPhone(changes) => self
                .w
                .link
                .send(LinkMsg::Send(Message::Settings(Settings { changes }))),
            SettingsEvent::View(v) => self.state.settings = v,
        }
    }

    fn on_ui(&mut self, a: Action) {
        match a {
            Action::Pause(which) => {
                let (feature, now) = match which {
                    Which::Mic => (MediaFeature::Mic, self.state.features.mic),
                    Which::Camera => (MediaFeature::Camera, self.state.features.camera),
                    Which::Speaker => (MediaFeature::Speaker, self.state.features.speaker),
                };
                self.w.media.send(MediaMsg::Pause {
                    feature,
                    paused: now == FeatureState::On,
                });
            }
            Action::Cycle(id) => {
                let current = self
                    .state
                    .settings
                    .values
                    .get(id)
                    .cloned()
                    .unwrap_or_default();
                if let Some(value) = next_value(id, &current) {
                    self.w.settings.send(SettingsMsg::Set {
                        id: id.to_owned(),
                        value,
                    });
                }
            }
            Action::Allow => self
                .w
                .session
                .send(SessionMsg::UserDecision { allow: true }),
            Action::Deny => self
                .w
                .session
                .send(SessionMsg::UserDecision { allow: false }),
            Action::Repair => (self.w.devices)(DeviceCommand::Repair),
            Action::RemovePhone(id) => {
                if let Some(phone_id) = id_from_hex(&id) {
                    self.w.session.send(SessionMsg::RemovePhone { phone_id });
                }
            }
            Action::BlockPhone(id, blocked) => {
                if let Some(phone_id) = id_from_hex(&id) {
                    self.w
                        .session
                        .send(SessionMsg::SetBlocked { phone_id, blocked });
                }
            }
            Action::Quit => (self.w.quit)(),
            Action::Open(_) => {}
        }
    }

    fn panel(&self) -> PanelState {
        let s = &self.state;
        let connection = match &s.session.phase {
            PhaseView::Idle => Connection::NotConnected,
            PhaseView::Approval { phone, code } => Connection::Approval {
                phone: phone.clone(),
                code: code.clone(),
                link: s.proving_link,
            },
            PhaseView::Active { phone, link } => Connection::Connected {
                phone: phone.clone(),
                link: s.link.link.unwrap_or(*link),
                weak: s.link.weak,
            },
            PhaseView::Held { phone } => Connection::Reconnecting {
                phone: phone.clone(),
                link: s.link.link.unwrap_or(s.proving_link),
            },
        };
        let feature = |f: FeatureState, stream: usize| match f {
            FeatureState::On if s.restarting[stream] => Feature::Restarting,
            FeatureState::On => Feature::On,
            FeatureState::Paused => Feature::Paused,
            FeatureState::Off => Feature::Off,
        };
        PanelState {
            connection,
            mic: feature(s.features.mic, 1),
            camera: feature(s.features.camera, 2),
            speaker: feature(s.features.speaker, 3),
            settings: s.settings.values.clone(),
            paired: s.settings.paired,
            phones: s
                .session
                .phones
                .iter()
                .map(|p| PhoneEntry {
                    id: hex(&p.id),
                    name: p.name.clone(),
                    blocked: p.blocked,
                })
                .collect(),
            addresses: s.addresses.clone(),
            repair: Repair {
                mic: !s.health.mic,
                cam: !s.health.cam,
                net: !s.health.net,
            },
            repairing: s.repairing,
            version: env!("CARGO_PKG_VERSION").to_owned(),
        }
    }
}

impl Hub for AppHub {
    type Msg = AppMsg;

    fn handle(&mut self, msg: AppMsg) {
        match msg {
            AppMsg::Link(e) => self.on_link(e),
            AppMsg::Session(e) => self.on_session(e),
            AppMsg::Media(e) => self.on_media(e),
            AppMsg::Settings(e) => self.on_settings(e),
            AppMsg::Health(h) => self.state.health = h,
            AppMsg::Repairing(r) => self.state.repairing = r,
            AppMsg::Ui(a) => self.on_ui(a),
            AppMsg::Addresses(a) => self.state.addresses = a,
        }
        let panel = self.panel();
        if panel != self.shown {
            self.shown = panel.clone();
            self.ui.publish(panel);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use owlmic_hub::{Inbox, mailbox};
    use owlmic_session::sessions::Phone;
    use owlmic_settings::store::PlainProtector;
    use std::sync::Mutex;

    struct Rig {
        hub: AppHub,
        link: Inbox<LinkMsg>,
        session: Inbox<SessionMsg>,
        media: Inbox<MediaMsg>,
        settings: Inbox<SettingsMsg>,
        devices: Arc<Mutex<Vec<DeviceCommand>>>,
        autostart: Arc<Mutex<Vec<bool>>>,
        ui: Publisher<PanelState>,
    }

    fn rig() -> Rig {
        let (link, link_in) = mailbox(16);
        let (session, session_in) = mailbox(16);
        let (media, media_in) = mailbox(16);
        let (settings, settings_in) = mailbox(16);
        let devices = Arc::new(Mutex::new(Vec::new()));
        let autostart = Arc::new(Mutex::new(Vec::new()));
        let (d, a) = (devices.clone(), autostart.clone());
        let ui = Publisher::new(PanelState::default(), || {});
        let dir = std::env::temp_dir().join(format!(
            "owlmic-app-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let store = Arc::new(Store::open(
            dir.join("owlmic.json"),
            Box::new(PlainProtector),
        ));
        let w = Wiring {
            link,
            session,
            media,
            settings,
            devices: Box::new(move |c| d.lock().unwrap().push(c)),
            autostart: Box::new(move |on| a.lock().unwrap().push(on)),
            quit: Box::new(|| {}),
        };
        Rig {
            hub: AppHub::new(w, store, ui.clone()),
            link: link_in,
            session: session_in,
            media: media_in,
            settings: settings_in,
            devices,
            autostart,
            ui,
        }
    }

    #[test]
    fn a_welcome_sends_the_pairings_settings_and_starts_its_settings() {
        let mut r = rig();
        let phone = Phone {
            id: [7; 16],
            name: "Pixel 8".into(),
            model: "Pixel 8".into(),
            static_pub: String::new(),
        };
        r.hub.handle(AppMsg::Session(SessionEvent::Welcome {
            conn: 3,
            session_id: [1; 16],
            phone,
        }));
        assert!(matches!(
            r.link.try_recv(),
            Some(LinkMsg::Welcome { conn: 3, .. })
        ));
        assert!(
            matches!(r.settings.try_recv(), Some(SettingsMsg::Session(Some(k))) if k == hex(&[7; 16]))
        );
        r.hub.handle(AppMsg::Session(SessionEvent::Ended {
            session_id: [1; 16],
        }));
        assert!(matches!(
            r.link.try_recv(),
            Some(LinkMsg::SessionEnded { .. })
        ));
        assert!(matches!(r.media.try_recv(), Some(MediaMsg::Ended)));
    }

    #[test]
    fn phone_messages_reach_the_hubs_that_act_on_them() {
        let mut r = rig();
        r.hub
            .handle(AppMsg::Link(LinkEvent::FromPhone(Message::State(
                State::default(),
            ))));
        assert!(matches!(
            r.media.try_recv(),
            Some(MediaMsg::FromPhone(Message::State(_)))
        ));
        r.hub
            .handle(AppMsg::Link(LinkEvent::FromPhone(Message::Settings(
                Settings { changes: vec![] },
            ))));
        assert!(matches!(
            r.settings.try_recv(),
            Some(SettingsMsg::FromPhone(_))
        ));
        r.hub.handle(AppMsg::Link(LinkEvent::Hello {
            conn: 1,
            phone: Phone {
                id: [1; 16],
                name: "A".into(),
                model: "A".into(),
                static_pub: String::new(),
            },
            resume: None,
        }));
        assert!(matches!(
            r.session.try_recv(),
            Some(SessionMsg::Hello { conn: 1, .. })
        ));
    }

    #[test]
    fn media_commands_go_to_the_devices_and_settings_apply_live() {
        let mut r = rig();
        r.hub.handle(AppMsg::Media(MediaEvent::Mic(true)));
        assert_eq!(
            r.devices.lock().unwrap().as_slice(),
            &[DeviceCommand::Mic(true)]
        );
        r.hub.handle(AppMsg::Settings(SettingsEvent::Apply(vec![
            ("general.startWithWindows".into(), "off".into()),
            ("camera.mirror".into(), "on".into()),
        ])));
        assert_eq!(r.autostart.lock().unwrap().as_slice(), &[false]);
        assert!(
            matches!(r.media.try_recv(), Some(MediaMsg::Setting { id, .. }) if id == "camera.mirror")
        );
    }

    #[test]
    fn the_panel_shows_the_approval_and_tiles_cycle_settings() {
        let mut r = rig();
        r.hub
            .handle(AppMsg::Session(SessionEvent::Changed(SessionView {
                phase: PhaseView::Approval {
                    phone: "Pixel 8".into(),
                    code: "4821".into(),
                },
                phones: vec![],
            })));
        assert!(
            matches!(&r.ui.read().connection, Connection::Approval { code, .. } if code == "4821")
        );
        let mut values = std::collections::BTreeMap::new();
        values.insert("camera.framing".to_owned(), "fill".to_owned());
        r.hub
            .handle(AppMsg::Settings(SettingsEvent::View(SettingsView {
                values,
                paired: true,
            })));
        r.hub.handle(AppMsg::Ui(Action::Cycle("camera.framing")));
        assert!(
            matches!(r.settings.try_recv(), Some(SettingsMsg::Set { value, .. }) if value == "fit")
        );
        r.hub.handle(AppMsg::Ui(Action::Allow));
        assert!(matches!(
            r.session.try_recv(),
            Some(SessionMsg::UserDecision { allow: true })
        ));
    }

    #[test]
    fn next_values_wrap_around() {
        assert_eq!(next_value("camera.frameRate", "60").as_deref(), Some("24"));
        assert_eq!(
            next_value("camera.frameRate", "bogus").as_deref(),
            Some("24")
        );
        assert_eq!(next_value("nope", "x"), None);
    }
}
