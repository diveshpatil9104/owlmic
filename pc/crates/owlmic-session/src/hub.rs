//! The Session Hub's thread: it answers the Link Hub's questions about phones and owns the approve
//! gate (SYSTEM_DESIGN section 35). It talks only to the App Hub, through `emit`.

use crate::sessions::{Decision, Expired, Phase, Phone, Sessions};
use owlmic_proto::crypto::random_bytes;
use owlmic_proto::messages::RejectReason;
use owlmic_settings::store::Store;
use std::sync::Arc;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

#[derive(Debug)]
pub enum SessionMsg {
    /// A phone said HELLO on connection `conn`.
    Hello {
        conn: u64,
        phone: Phone,
        resume: Option<[u8; 16]>,
    },
    /// The phone's PROOF checked out. `decision` is what [`SessionEvent::Decided`] said.
    Proven {
        conn: u64,
        phone: Phone,
        decision: Decision,
        code: String,
        link: u8,
    },
    /// Allow or Deny in the approve gate.
    UserDecision {
        allow: bool,
    },
    LinkUp {
        session_id: [u8; 16],
        link: u8,
    },
    LinkLost {
        session_id: [u8; 16],
    },
    ConnClosed {
        conn: u64,
    },
    Bye {
        session_id: [u8; 16],
    },
    RemovePhone {
        phone_id: [u8; 16],
    },
    SetBlocked {
        phone_id: [u8; 16],
        blocked: bool,
    },
}

#[derive(Debug, Clone, PartialEq)]
pub enum SessionEvent {
    Decided {
        conn: u64,
        decision: Decision,
    },
    Welcome {
        conn: u64,
        session_id: [u8; 16],
        phone: Phone,
    },
    Rejected {
        conn: u64,
        reason: RejectReason,
    },
    Ended {
        session_id: [u8; 16],
    },
    Changed(SessionView),
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum PhaseView {
    #[default]
    Idle,
    Approval {
        phone: String,
        code: String,
    },
    Active {
        phone: String,
        link: u8,
    },
    Held {
        phone: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhoneView {
    pub id: [u8; 16],
    pub name: String,
    pub blocked: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SessionView {
    pub phase: PhaseView,
    pub phones: Vec<PhoneView>,
}

pub struct SessionHub {
    store: Arc<Store>,
    sessions: Sessions,
    emit: Box<dyn Fn(SessionEvent) + Send>,
}

impl SessionHub {
    pub fn new(store: Arc<Store>, emit: impl Fn(SessionEvent) + Send + 'static) -> Self {
        let hub = Self {
            store,
            sessions: Sessions::default(),
            emit: Box::new(emit),
        };
        hub.publish();
        hub
    }

    fn publish(&self) {
        (self.emit)(SessionEvent::Changed(self.view()));
    }

    pub fn view(&self) -> SessionView {
        let phase = match self.sessions.phase() {
            Phase::Idle => PhaseView::Idle,
            Phase::Pending { phone, code, .. } => PhaseView::Approval {
                phone: phone.name.clone(),
                code: code.clone(),
            },
            Phase::Active { phone, link, .. } => PhaseView::Active {
                phone: phone.name.clone(),
                link: *link,
            },
            Phase::Held { phone, .. } => PhaseView::Held {
                phone: phone.name.clone(),
            },
        };
        let phones = self.store.read(|d| {
            d.phones
                .iter()
                .filter_map(|(id, r)| {
                    Some(PhoneView {
                        id: crate::id_from_hex(id)?,
                        name: r.name.clone(),
                        blocked: r.blocked,
                    })
                })
                .collect()
        });
        SessionView { phase, phones }
    }
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

impl owlmic_hub::Hub for SessionHub {
    type Msg = SessionMsg;

    fn handle(&mut self, msg: SessionMsg) {
        let now = Instant::now();
        match msg {
            SessionMsg::Hello {
                conn,
                phone,
                resume,
            } => {
                let decision = self.store.read(|d| self.sessions.decide(d, &phone, resume));
                (self.emit)(SessionEvent::Decided { conn, decision });
            }
            SessionMsg::Proven {
                conn,
                phone,
                decision,
                code,
                link,
            } => match decision {
                Decision::Known => {
                    let session_id = random_bytes::<16>();
                    self.store.update(|d| {
                        self.sessions
                            .activate(d, phone.clone(), session_id, link, now_secs())
                    });
                    (self.emit)(SessionEvent::Welcome {
                        conn,
                        session_id,
                        phone,
                    });
                }
                Decision::Resume => {
                    if let Some((session_id, _)) = self.sessions.current() {
                        self.sessions.resumed(session_id, link);
                        (self.emit)(SessionEvent::Welcome {
                            conn,
                            session_id,
                            phone,
                        });
                    }
                }
                Decision::New => self.sessions.begin_approval(conn, phone, code, link, now),
                Decision::Busy { .. } | Decision::Blocked => {}
            },
            SessionMsg::UserDecision { allow: true } => {
                let session_id = random_bytes::<16>();
                let approved = self
                    .store
                    .update(|d| self.sessions.approve(d, session_id, now_secs()));
                if let Some((conn, phone)) = approved {
                    (self.emit)(SessionEvent::Welcome {
                        conn,
                        session_id,
                        phone,
                    });
                }
            }
            SessionMsg::UserDecision { allow: false } => {
                if let Some(conn) = self.sessions.deny() {
                    (self.emit)(SessionEvent::Rejected {
                        conn,
                        reason: RejectReason::Denied,
                    });
                }
            }
            SessionMsg::LinkUp { session_id, link } => {
                self.sessions.resumed(session_id, link);
                self.sessions.link_changed(session_id, link);
            }
            SessionMsg::LinkLost { session_id } => self.sessions.link_lost(session_id, now),
            SessionMsg::ConnClosed { conn } => self.sessions.connection_closed(conn),
            SessionMsg::Bye { session_id } => {
                self.sessions.end(session_id);
                (self.emit)(SessionEvent::Ended { session_id });
            }
            SessionMsg::RemovePhone { phone_id } => {
                let key = crate::hex(&phone_id);
                self.store.update(|d| {
                    d.phones.remove(&key);
                    d.pairings.remove(&key);
                });
                if let Some((session_id, p)) = self.sessions.current()
                    && p.id == phone_id
                {
                    self.sessions.end(session_id);
                    (self.emit)(SessionEvent::Ended { session_id });
                }
            }
            SessionMsg::SetBlocked { phone_id, blocked } => {
                self.store.update(|d| {
                    if let Some(r) = d.phones.get_mut(&crate::hex(&phone_id)) {
                        r.blocked = blocked;
                    }
                });
                if blocked
                    && let Some((session_id, p)) = self.sessions.current()
                    && p.id == phone_id
                {
                    self.sessions.end(session_id);
                    (self.emit)(SessionEvent::Ended { session_id });
                }
            }
        }
        self.publish();
    }

    fn tick(&mut self, now: Instant) {
        match self.sessions.tick(now) {
            Some(Expired::Session(session_id)) => (self.emit)(SessionEvent::Ended { session_id }),
            Some(Expired::Approval { conn }) => {
                (self.emit)(SessionEvent::Rejected {
                    conn,
                    reason: RejectReason::Denied,
                });
            }
            None => return,
        }
        self.publish();
    }

    fn next_deadline(&self) -> Option<Instant> {
        self.sessions.next_deadline()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use owlmic_hub::Hub;
    use owlmic_settings::store::PlainProtector;
    use std::sync::Mutex;

    fn hub() -> (SessionHub, Arc<Mutex<Vec<SessionEvent>>>) {
        let dir = std::env::temp_dir().join(format!(
            "owlmic-session-hub-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        let store = Arc::new(Store::open(
            dir.join("owlmic.json"),
            Box::new(PlainProtector),
        ));
        let events = Arc::new(Mutex::new(Vec::new()));
        let e = events.clone();
        (
            SessionHub::new(store, move |ev| e.lock().unwrap().push(ev)),
            events,
        )
    }

    fn phone() -> Phone {
        Phone {
            id: [1; 16],
            name: "Pixel 8".into(),
            model: "Pixel 8".into(),
            static_pub: "key".into(),
        }
    }

    #[test]
    fn the_approve_gate_shows_the_code_and_allow_welcomes_the_phone() {
        let (mut h, events) = hub();
        h.handle(SessionMsg::Hello {
            conn: 1,
            phone: phone(),
            resume: None,
        });
        assert!(events.lock().unwrap().contains(&SessionEvent::Decided {
            conn: 1,
            decision: Decision::New
        }));
        h.handle(SessionMsg::Proven {
            conn: 1,
            phone: phone(),
            decision: Decision::New,
            code: "4821".into(),
            link: 3,
        });
        assert_eq!(
            h.view().phase,
            PhaseView::Approval {
                phone: "Pixel 8".into(),
                code: "4821".into()
            }
        );
        h.handle(SessionMsg::UserDecision { allow: true });
        assert!(
            events
                .lock()
                .unwrap()
                .iter()
                .any(|e| matches!(e, SessionEvent::Welcome { conn: 1, .. }))
        );
        assert_eq!(h.view().phones.len(), 1);
    }

    #[test]
    fn deny_rejects_and_removing_a_phone_ends_its_session() {
        let (mut h, events) = hub();
        h.handle(SessionMsg::Proven {
            conn: 2,
            phone: phone(),
            decision: Decision::New,
            code: "1".into(),
            link: 3,
        });
        h.handle(SessionMsg::UserDecision { allow: false });
        assert!(events.lock().unwrap().contains(&SessionEvent::Rejected {
            conn: 2,
            reason: RejectReason::Denied
        }));
        h.handle(SessionMsg::Proven {
            conn: 3,
            phone: phone(),
            decision: Decision::Known,
            code: "1".into(),
            link: 3,
        });
        h.handle(SessionMsg::RemovePhone { phone_id: [1; 16] });
        assert!(
            events
                .lock()
                .unwrap()
                .iter()
                .any(|e| matches!(e, SessionEvent::Ended { .. }))
        );
        assert_eq!(h.view().phase, PhaseView::Idle);
    }
}
