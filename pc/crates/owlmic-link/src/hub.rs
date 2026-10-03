//! The Link Hub's thread (SYSTEM_DESIGN section 14): it drives each connection's handshake,
//! decides which link carries the session, keeps heartbeats, reports and the recovery ladder. It
//! asks the Session Hub about phones only through `emit` and the App Hub.

use crate::handshake::{Ours, ServerHandshake};
use crate::media::{OutPath, Routes};
use crate::monitor::{HEARTBEAT, Monitor, REPORT_EVERY, StatsMark, report};
use crate::ratelimit::HandshakeLimit;
use crate::reconnect::{Reconnector, Step};
use crate::server::{Closer, SharedWriter};
use crate::switcher::Switcher;
use crate::wire::CipherSlot;
use owlmic_proto::crypto::{Cipher, SessionKeys};
use owlmic_proto::frame::{kind, stream};
use owlmic_proto::messages::{
    FeatureState, Message, Pending, Ping, Reject, RejectReason, StreamRef, Switch,
};
use owlmic_session::sessions::Phone;
use owlmic_session::{Decision, Identity};
use owlmic_settings::store::Versioned;
use std::collections::{BTreeMap, HashMap};
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

/// A handshake step that takes longer than this is abandoned (protocol/README.md, section 4).
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(3);
/// A link this lossy or slow counts as weak (the yellow indicator).
const WEAK_LOSS_PCT: f64 = 5.0;
const WEAK_RTT_MS: u32 = 250;

pub struct Opened {
    pub conn: u64,
    pub link: u8,
    pub peer: SocketAddr,
    pub writer: SharedWriter,
    pub rx: CipherSlot,
    pub close: Closer,
}

pub enum Carrier {
    Udp(SocketAddr),
    Stream {
        conn: u64,
        writer: SharedWriter,
        link: u8,
    },
}

pub enum LinkMsg {
    Opened(Opened),
    Frame {
        conn: u64,
        kind: u8,
        payload: Vec<u8>,
    },
    Closed {
        conn: u64,
    },
    CarrierUp {
        carrier: Carrier,
        session_id: [u8; 16],
    },
    CarrierDown {
        conn: u64,
    },
    /// The Session Hub's answer to [`LinkEvent::Hello`].
    Decided {
        conn: u64,
        decision: Decision,
    },
    Welcome {
        conn: u64,
        session_id: [u8; 16],
        settings: BTreeMap<String, Versioned>,
    },
    Reject {
        conn: u64,
        reason: RejectReason,
        owner: Option<String>,
    },
    SessionEnded {
        session_id: [u8; 16],
    },
    /// A message for the phone on the session's active link.
    Send(Message),
    /// The PC joined or left a network: announce again.
    NetworkChanged,
}

#[derive(Debug, Clone, PartialEq)]
pub enum LinkEvent {
    Hello {
        conn: u64,
        phone: Phone,
        resume: Option<[u8; 16]>,
    },
    Proven {
        conn: u64,
        phone: Phone,
        decision: Decision,
        code: String,
        link: u8,
    },
    ConnClosed {
        conn: u64,
    },
    LinkUp {
        session_id: [u8; 16],
        link: u8,
    },
    LinkLost {
        session_id: [u8; 16],
    },
    Bye {
        session_id: [u8; 16],
    },
    /// A message from the phone for the other hubs: STATE, SETTINGS, REPORT, STREAM_*.
    FromPhone(Message),
    /// A stream that should flow has stopped; `tell_user` after 5 seconds without recovery.
    Stalled {
        stream: u8,
        tell_user: bool,
    },
    Recovered {
        stream: u8,
    },
    View(LinkView),
}

#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct LinkView {
    /// The link carrying the session, if one does.
    pub link: Option<u8>,
    pub weak: bool,
    pub rtt_ms: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum Stage {
    AwaitHello,
    Deciding,
    AwaitProof,
    Approving,
    AwaitWelcome,
    Live,
}

struct Conn {
    link: u8,
    peer: SocketAddr,
    writer: SharedWriter,
    rx: CipherSlot,
    close: Closer,
    stage: Stage,
    since: Instant,
    hs: Option<ServerHandshake>,
    phone: Option<Phone>,
    decision: Option<Decision>,
    keys: Option<SessionKeys>,
    monitor: Monitor,
}

impl Conn {
    fn send(&self, msg: &Message) {
        let _ = self
            .writer
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .send(msg);
    }
}

pub struct LinkHub {
    ours: Arc<Identity>,
    bt_addr: Option<String>,
    routes: Arc<Routes>,
    udp: Arc<UdpSocket>,
    busy: Arc<AtomicBool>,
    emit: Box<dyn Fn(LinkEvent) + Send>,
    conns: HashMap<u64, Conn>,
    session: Option<[u8; 16]>,
    switcher: Switcher,
    limit: HandshakeLimit,
    udp_carriers: HashMap<IpAddr, SocketAddr>,
    adb_carrier: Option<(u64, SharedWriter)>,
    expected: [bool; 4],
    stalled: [bool; 4],
    reconnect: [Reconnector; 4],
    marks: BTreeMap<u8, StatsMark>,
    last_report: Instant,
    next_tick: Option<Instant>,
    view: LinkView,
    announce: Option<Box<dyn Fn() + Send>>,
}

impl LinkHub {
    pub fn new(
        ours: Arc<Identity>,
        routes: Arc<Routes>,
        udp: Arc<UdpSocket>,
        busy: Arc<AtomicBool>,
        emit: impl Fn(LinkEvent) + Send + 'static,
    ) -> Self {
        Self {
            ours,
            bt_addr: None,
            routes,
            udp,
            busy,
            emit: Box::new(emit),
            conns: HashMap::new(),
            session: None,
            switcher: Switcher::default(),
            limit: HandshakeLimit::default(),
            udp_carriers: HashMap::new(),
            adb_carrier: None,
            expected: [false; 4],
            stalled: [false; 4],
            reconnect: Default::default(),
            marks: BTreeMap::new(),
            last_report: Instant::now(),
            next_tick: None,
            view: LinkView::default(),
            announce: None,
        }
    }

    /// The PC's Bluetooth address, sent in HELLO_ACK so the phone can find it over Bluetooth later.
    pub fn with_bt_addr(mut self, addr: Option<String>) -> Self {
        self.bt_addr = addr;
        self
    }

    /// How to announce on the networks, for [`LinkMsg::NetworkChanged`].
    pub fn with_announce(mut self, announce: impl Fn() + Send + 'static) -> Self {
        self.announce = Some(Box::new(announce));
        self
    }

    fn close(&mut self, conn: u64) {
        if let Some(c) = self.conns.get(&conn) {
            (c.close)();
        }
    }

    fn reject(&mut self, conn: u64, reason: RejectReason, owner: Option<String>) {
        if let Some(c) = self.conns.get(&conn) {
            c.send(&Message::Reject(Reject { reason, owner }));
        }
        self.close(conn);
    }

    fn on_opened(&mut self, o: Opened) {
        let now = Instant::now();
        if !self.limit.allow(o.peer.ip(), now) {
            (o.close)();
            return;
        }
        let conn = Conn {
            link: o.link,
            peer: o.peer,
            writer: o.writer,
            rx: o.rx,
            close: o.close,
            stage: Stage::AwaitHello,
            since: now,
            hs: None,
            phone: None,
            decision: None,
            keys: None,
            monitor: Monitor::new(o.link, now),
        };
        self.conns.insert(o.conn, conn);
        self.schedule(now + HANDSHAKE_TIMEOUT);
    }

    fn on_frame(&mut self, conn_id: u64, kind: u8, payload: Vec<u8>) {
        let now = Instant::now();
        let Some(c) = self.conns.get_mut(&conn_id) else {
            return;
        };
        c.monitor.heard(now);
        match (c.stage, kind) {
            (Stage::AwaitHello, kind::HELLO) => match ServerHandshake::hello(&payload) {
                Ok((hs, hello, phone)) => {
                    c.hs = Some(hs);
                    c.phone = Some(phone.clone());
                    c.stage = Stage::Deciding;
                    c.since = now;
                    let resume = hello
                        .resume
                        .as_deref()
                        .and_then(owlmic_session::id_from_hex);
                    (self.emit)(LinkEvent::Hello {
                        conn: conn_id,
                        phone,
                        resume,
                    });
                }
                Err(crate::handshake::HandshakeError::Version) => {
                    self.reject(conn_id, RejectReason::Version, None)
                }
                Err(_) => self.close(conn_id),
            },
            (Stage::AwaitProof, kind::PROOF) => {
                if !c.hs.as_ref().is_some_and(|hs| hs.proof(&payload).is_ok()) {
                    return self.close(conn_id);
                }
                let (Some(decision), Some(phone)) = (c.decision.clone(), c.phone.clone()) else {
                    return;
                };
                let code = c.hs.as_ref().map(|h| h.code()).unwrap_or_default();
                if decision == Decision::New {
                    c.send(&Message::Pending(Pending { code: code.clone() }));
                    c.stage = Stage::Approving;
                } else {
                    c.stage = Stage::AwaitWelcome;
                }
                c.since = now;
                let link = c.link;
                (self.emit)(LinkEvent::Proven {
                    conn: conn_id,
                    phone,
                    decision,
                    code,
                    link,
                });
            }
            (Stage::Live, _) => self.on_live_frame(conn_id, kind, &payload, now),
            _ => {}
        }
    }

    fn on_live_frame(&mut self, conn_id: u64, kind: u8, payload: &[u8], now: Instant) {
        let Ok(Some(msg)) = Message::from_payload(kind, payload) else {
            return;
        };
        let Some(c) = self.conns.get_mut(&conn_id) else {
            return;
        };
        match msg {
            Message::Ping(p) => c.send(&Message::Pong(p)),
            Message::Pong(p) => c.monitor.pong(p.t, self.routes.now_us(), now),
            Message::Bye(_) => {
                if let Some(session_id) = self.session {
                    (self.emit)(LinkEvent::Bye { session_id });
                    self.end_session();
                }
            }
            Message::State(s) => {
                self.expected[stream::MIC as usize] = s.mic == FeatureState::On;
                self.expected[stream::CAMERA as usize] = s.camera == FeatureState::On;
                (self.emit)(LinkEvent::FromPhone(Message::State(s)));
            }
            Message::Report(r) => {
                self.view.weak = r.loss_pct >= WEAK_LOSS_PCT || r.rtt_ms >= WEAK_RTT_MS;
                self.publish_view();
                (self.emit)(LinkEvent::FromPhone(Message::Report(r)));
            }
            m @ (Message::Settings(_)
            | Message::StreamStart(_)
            | Message::StreamStop(_)
            | Message::RestartStream(_)) => (self.emit)(LinkEvent::FromPhone(m)),
            _ => {}
        }
    }

    fn on_decided(&mut self, conn_id: u64, decision: Decision) {
        let ours = self.ours.clone();
        let o = Ours {
            keys: &ours.keys,
            pc_id: ours.pc_id,
            name: &ours.name,
            bt_addr: self.bt_addr.clone(),
        };
        let Some(c) = self.conns.get_mut(&conn_id) else {
            return;
        };
        if c.stage != Stage::Deciding {
            return;
        }
        let Some(ack) = c.hs.as_mut().and_then(|hs| hs.ack(&o, &decision).ok()) else {
            return self.close(conn_id);
        };
        let _ = c
            .writer
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .send_raw(kind::HELLO_ACK, &ack);
        match decision {
            Decision::Busy { owner } => self.reject(conn_id, RejectReason::Busy, Some(owner)),
            Decision::Blocked => self.reject(conn_id, RejectReason::Blocked, None),
            decision => {
                c.decision = Some(decision);
                c.stage = Stage::AwaitProof;
                c.since = Instant::now();
            }
        }
    }

    fn on_welcome(
        &mut self,
        conn_id: u64,
        session_id: [u8; 16],
        settings: &BTreeMap<String, Versioned>,
    ) {
        let now = Instant::now();
        let Some(c) = self.conns.get_mut(&conn_id) else {
            return;
        };
        let Some(hs) = c.hs.take() else { return };
        let welcome = hs.welcome(session_id, settings);
        let Some(keys) = hs.into_keys() else { return };
        // Decryption must be on before WELCOME leaves: the phone encrypts its very next frame.
        if crate::is_wireless(c.link) {
            *c.rx.lock().unwrap_or_else(|p| p.into_inner()) = Some(Cipher::new(&keys.phone_to_pc));
        }
        {
            let mut w = c.writer.lock().unwrap_or_else(|p| p.into_inner());
            let _ = w.send(&welcome);
            if crate::is_wireless(c.link) {
                w.encrypt_with(Cipher::new(&keys.pc_to_phone));
            }
        }
        c.keys = Some(keys);
        c.stage = Stage::Live;
        c.monitor = Monitor::new(c.link, now);
        let (link, ip) = (c.link, c.peer.ip());
        self.routes.set_peer(ip, crate::is_wireless(link));
        let first = self.session != Some(session_id);
        self.session = Some(session_id);
        self.busy.store(true, Ordering::Relaxed);
        self.switcher.add(conn_id, link, now);
        if first || self.switcher.active() == Some(conn_id) {
            self.activate(conn_id);
        }
        self.schedule(now + HEARTBEAT);
    }

    /// Media moves to `conn`: its keys, its carrier, and the phone is told with SWITCH.
    fn activate(&mut self, conn_id: u64) {
        let (Some(session_id), Some(c)) = (self.session, self.conns.get(&conn_id)) else {
            return;
        };
        let Some(keys) = c.keys.as_ref() else { return };
        self.routes.install(session_id, keys);
        c.send(&Message::Switch(Switch { link: c.link }));
        let link = c.link;
        self.update_out();
        self.view.link = Some(link);
        self.publish_view();
        (self.emit)(LinkEvent::LinkUp { session_id, link });
    }

    fn update_out(&mut self) {
        let out = self
            .switcher
            .active()
            .and_then(|a| self.conns.get(&a))
            .and_then(|c| match c.link {
                crate::LINK_USB_DEBUGGING => {
                    self.adb_carrier.as_ref().map(|(_, w)| OutPath::Stream {
                        writer: w.clone(),
                        encrypt: false,
                    })
                }
                crate::LINK_BLUETOOTH => Some(OutPath::Stream {
                    writer: c.writer.clone(),
                    encrypt: true,
                }),
                link => self.udp_carriers.get(&c.peer.ip()).map(|to| OutPath::Udp {
                    socket: self.udp.clone(),
                    to: *to,
                    encrypt: crate::is_wireless(link),
                }),
            });
        self.routes.set_out(out);
    }

    fn on_closed(&mut self, conn_id: u64) {
        let Some(c) = self.conns.remove(&conn_id) else {
            return;
        };
        if c.stage < Stage::Live {
            (self.emit)(LinkEvent::ConnClosed { conn: conn_id });
            return;
        }
        let now = Instant::now();
        match self.switcher.remove(conn_id, now) {
            Some(next) => self.activate(next),
            None if self.switcher.active().is_none() => {
                self.routes.set_out(None);
                self.view.link = None;
                self.publish_view();
                if let Some(session_id) = self.session {
                    (self.emit)(LinkEvent::LinkLost { session_id });
                }
            }
            None => {}
        }
    }

    fn end_session(&mut self) {
        let live: Vec<u64> = self
            .conns
            .iter()
            .filter(|(_, c)| c.stage == Stage::Live)
            .map(|(id, _)| *id)
            .collect();
        for id in live {
            self.close(id);
            self.conns.remove(&id);
        }
        self.session = None;
        self.switcher.clear();
        self.routes.clear();
        self.udp_carriers.clear();
        self.adb_carrier = None;
        self.expected = [false; 4];
        self.busy.store(false, Ordering::Relaxed);
        self.view = LinkView::default();
        self.publish_view();
    }

    fn on_carrier(&mut self, carrier: Carrier, session_id: [u8; 16]) {
        if self.session != Some(session_id) {
            return;
        }
        match carrier {
            Carrier::Udp(addr) => {
                self.udp_carriers.insert(addr.ip(), addr);
            }
            Carrier::Stream { conn, writer, link } if link == crate::LINK_USB_DEBUGGING => {
                self.adb_carrier = Some((conn, writer));
            }
            Carrier::Stream { .. } => {}
        }
        self.update_out();
    }

    fn publish_view(&self) {
        (self.emit)(LinkEvent::View(self.view));
    }

    fn schedule(&mut self, at: Instant) {
        self.next_tick = Some(self.next_tick.map_or(at, |t| t.min(at)));
    }

    fn heartbeat(&mut self, now: Instant) {
        let ping = Message::Ping(Ping {
            t: self.routes.now_us(),
        });
        let mut dead = Vec::new();
        for (id, c) in &self.conns {
            match c.stage {
                Stage::Live if c.monitor.is_dead(now) => dead.push(*id),
                Stage::Live => c.send(&ping),
                Stage::Approving => {}
                _ if now.duration_since(c.since) > HANDSHAKE_TIMEOUT => dead.push(*id),
                _ => {}
            }
        }
        for id in dead {
            self.close(id);
            self.on_closed(id);
        }
        if let Some(next) = self.switcher.upgrade(now) {
            self.activate(next);
        }
        if let Some(active) = self.switcher.active() {
            self.view.rtt_ms = self.conns.get(&active).map_or(0, |c| c.monitor.rtt_ms());
            if now.duration_since(self.last_report) >= REPORT_EVERY {
                let r = report(
                    &[
                        (stream::MIC, &self.routes.stats[1]),
                        (stream::CAMERA, &self.routes.stats[2]),
                    ],
                    &mut self.marks,
                    now.duration_since(self.last_report),
                    self.view.rtt_ms,
                );
                self.last_report = now;
                if let Some(c) = self.conns.get(&active) {
                    c.send(&Message::Report(r));
                }
            }
            self.check_streams(active, now);
        }
    }

    /// The recovery ladder for each stream the phone says is on.
    fn check_streams(&mut self, active: u64, now: Instant) {
        let now_us = self.routes.now_us();
        for s in [stream::MIC, stream::CAMERA] {
            let i = s as usize;
            if !self.expected[i] {
                self.reconnect[i].flowing();
                continue;
            }
            let last_us = self.routes.stats[i].last_packet_us.load(Ordering::Relaxed);
            let age = Duration::from_micros(now_us.saturating_sub(last_us));
            let last = now.checked_sub(age).unwrap_or(now);
            match self.reconnect[i].check(last, now) {
                Some(step) => self.take_step(active, s, step),
                None if age < crate::reconnect::STALL && self.stalled[i] => {
                    self.stalled[i] = false;
                    (self.emit)(LinkEvent::Recovered { stream: s });
                }
                None => {}
            }
            if age >= crate::reconnect::STALL {
                self.stalled[i] = true;
                let tell_user = self.reconnect[i].should_tell_user(now);
                (self.emit)(LinkEvent::Stalled {
                    stream: s,
                    tell_user,
                });
            }
        }
    }

    fn take_step(&mut self, active: u64, s: u8, step: Step) {
        match step {
            Step::RestartSource | Step::RecreateChannel => {
                if let Some(c) = self.conns.get(&active) {
                    c.send(&Message::RestartStream(StreamRef { stream: s }));
                    if s == stream::CAMERA {
                        c.send(&Message::KeyframeRequest);
                    }
                }
                if step == Step::RecreateChannel {
                    self.udp_carriers.clear();
                    self.adb_carrier = None;
                    self.update_out();
                }
            }
            Step::Rehandshake => self.close(active),
            Step::SwitchLink => {
                if self.switcher.standby().is_some() {
                    self.close(active);
                    self.on_closed(active);
                }
            }
            Step::Hold => {}
        }
    }
}

impl owlmic_hub::Hub for LinkHub {
    type Msg = LinkMsg;

    fn handle(&mut self, msg: LinkMsg) {
        match msg {
            LinkMsg::Opened(o) => self.on_opened(o),
            LinkMsg::Frame {
                conn,
                kind,
                payload,
            } => self.on_frame(conn, kind, payload),
            LinkMsg::Closed { conn } => self.on_closed(conn),
            LinkMsg::CarrierUp {
                carrier,
                session_id,
            } => self.on_carrier(carrier, session_id),
            LinkMsg::CarrierDown { conn } => {
                if self.adb_carrier.as_ref().is_some_and(|(c, _)| *c == conn) {
                    self.adb_carrier = None;
                    self.update_out();
                }
            }
            LinkMsg::Decided { conn, decision } => self.on_decided(conn, decision),
            LinkMsg::Welcome {
                conn,
                session_id,
                settings,
            } => self.on_welcome(conn, session_id, &settings),
            LinkMsg::Reject {
                conn,
                reason,
                owner,
            } => self.reject(conn, reason, owner),
            LinkMsg::SessionEnded { session_id } => {
                if self.session == Some(session_id) {
                    if let Some(c) = self.switcher.active().and_then(|a| self.conns.get(&a)) {
                        c.send(&Message::Bye(owlmic_proto::messages::Bye {
                            reason: Some("ended".into()),
                        }));
                    }
                    self.end_session();
                }
            }
            LinkMsg::Send(m) => {
                if let Some(c) = self.switcher.active().and_then(|a| self.conns.get(&a)) {
                    c.send(&m);
                }
            }
            LinkMsg::NetworkChanged => {
                if let Some(a) = &self.announce {
                    a();
                }
            }
        }
    }

    fn tick(&mut self, now: Instant) {
        self.next_tick = None;
        self.heartbeat(now);
        if !self.conns.is_empty() {
            self.schedule(now + HEARTBEAT);
        }
    }

    fn next_deadline(&self) -> Option<Instant> {
        self.next_tick
    }
}
