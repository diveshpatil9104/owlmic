//! The whole PC side over loopback: a simulated phone connects through the real server, carrier
//! and Link Hub threads, while the test plays the App and Session Hubs.

use crate::handshake::phone::SimPhone;
use crate::hub::{LinkEvent, LinkHub, LinkMsg};
use crate::media::{MediaSink, Routes, carrier_hello_packet};
use crate::server::{self, Shared};
use crate::wire::{Item, Reader, Writer};
use owlmic_proto::crypto::KeyPair;
use owlmic_proto::frame::{CHANNEL_CONTROL, CHANNEL_MEDIA, MediaHeader, kind, stream};
use owlmic_proto::messages::{FeatureState, Message, State};
use owlmic_session::{Decision, Identity};
use std::collections::BTreeMap;
use std::io::Write;
use std::net::{TcpListener, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64};
use std::sync::{Arc, Mutex, mpsc};
use std::time::Duration;

struct Collect(Mutex<Vec<Vec<u8>>>);
impl MediaSink for Collect {
    fn deliver(&self, _h: &MediaHeader, p: &[u8]) {
        self.0.lock().unwrap().push(p.to_vec());
    }
}

fn next(events: &mpsc::Receiver<LinkEvent>, want: impl Fn(&LinkEvent) -> bool) -> LinkEvent {
    loop {
        let e = events
            .recv_timeout(Duration::from_secs(3))
            .expect("an event from the Link Hub");
        if want(&e) {
            return e;
        }
    }
}

fn control(r: &mut Reader<TcpStream>) -> (u8, Vec<u8>) {
    match r.read_item().unwrap() {
        Item::Control { kind, payload } => (kind, payload),
        Item::Media(_) => panic!("expected a control frame"),
    }
}

#[test]
fn a_phone_connects_streams_its_mic_and_hears_the_speaker() {
    let mic = Arc::new(Collect(Mutex::new(Vec::new())));
    let routes = Arc::new(Routes::new(Some(mic.clone()), None));
    let udp = Arc::new(UdpSocket::bind("127.0.0.1:0").unwrap());
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let (to_hub, inbox) = owlmic_hub::mailbox(64);
    let shared = Arc::new(Shared {
        routes: routes.clone(),
        to_hub: to_hub.clone(),
        next_conn: AtomicU64::new(1),
        net: Arc::new(crate::netinfo::Simple),
    });
    std::thread::spawn(move || server::serve(listener, shared));
    let (tx, events) = mpsc::channel();
    let ours = Arc::new(Identity {
        keys: KeyPair::generate(),
        pc_id: [9; 16],
        name: "DESKTOP-A".into(),
    });
    let hub = LinkHub::new(
        ours,
        routes.clone(),
        udp,
        Arc::new(AtomicBool::new(false)),
        move |e| {
            let _ = tx.send(e);
        },
    );
    owlmic_hub::spawn("link", hub, inbox);

    let mut phone = SimPhone::new(1);
    let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
    stream.write_all(&[CHANNEL_CONTROL]).unwrap();
    let mut w = Writer::new(stream.try_clone().unwrap());
    let mut r = Reader::new(stream, Arc::new(Mutex::new(None)));
    w.send_raw(kind::HELLO, &phone.hello(crate::LINK_USB_DEBUGGING, None))
        .unwrap();

    let LinkEvent::Hello { conn, .. } = next(&events, |e| matches!(e, LinkEvent::Hello { .. }))
    else {
        unreachable!()
    };
    to_hub.send(LinkMsg::Decided {
        conn,
        decision: Decision::Known,
    });
    let (k, ack) = control(&mut r);
    assert_eq!(k, kind::HELLO_ACK);
    w.send_raw(kind::PROOF, &phone.proof(&ack)).unwrap();
    let LinkEvent::Proven { link, .. } = next(&events, |e| matches!(e, LinkEvent::Proven { .. }))
    else {
        unreachable!()
    };
    assert_eq!(link, crate::LINK_USB_DEBUGGING);
    to_hub.send(LinkMsg::Welcome {
        conn,
        session_id: [4; 16],
        settings: BTreeMap::new(),
    });
    let (k, welcome) = control(&mut r);
    assert_eq!(k, kind::WELCOME);
    let Ok(Some(Message::Welcome(wm))) = Message::from_payload(k, &welcome) else {
        panic!()
    };
    assert!(phone.welcome_is_genuine(&wm));
    assert!(matches!(
        next(&events, |e| matches!(e, LinkEvent::LinkUp { .. })),
        LinkEvent::LinkUp { link: 1, .. }
    ));
    let (k, _) = control(&mut r);
    assert_eq!(k, kind::SWITCH);

    w.send(&Message::State(State {
        mic: FeatureState::On,
        camera: FeatureState::Off,
        speaker: FeatureState::On,
    }))
    .unwrap();
    next(&events, |e| {
        matches!(e, LinkEvent::FromPhone(Message::State(_)))
    });

    let keys = phone.session.as_ref().unwrap();
    let mut media = TcpStream::connect(("127.0.0.1", port)).unwrap();
    media.write_all(&[CHANNEL_MEDIA]).unwrap();
    let mut mw = Writer::new(media.try_clone().unwrap());
    mw.media(&carrier_hello_packet([4; 16], &keys.auth))
        .unwrap();
    let mic_packet = [
        MediaHeader {
            stream: stream::MIC,
            keyframe: false,
            seq: 1,
            timestamp_us: 0,
        }
        .encode()
        .as_slice(),
        b"pcm",
    ]
    .concat();
    mw.media(&mic_packet).unwrap();
    for _ in 0..100 {
        if !mic.0.lock().unwrap().is_empty() {
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    assert_eq!(*mic.0.lock().unwrap(), vec![b"pcm".to_vec()]);

    let mut mr = Reader::new(media, Arc::new(Mutex::new(None)));
    routes.send_speaker(10_000, b"speaker");
    let Item::Media(back) = mr.read_item().unwrap() else {
        panic!("expected speaker media")
    };
    let h = MediaHeader::decode(&back).unwrap();
    assert_eq!(h.stream, stream::SPEAKER);
    assert_eq!(&back[MediaHeader::LEN..], b"speaker");
}

#[test]
fn a_busy_pc_turns_a_second_phone_away_with_the_owners_name() {
    let routes = Arc::new(Routes::new(None, None));
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let (to_hub, inbox) = owlmic_hub::mailbox(64);
    let shared = Arc::new(Shared {
        routes: routes.clone(),
        to_hub: to_hub.clone(),
        next_conn: AtomicU64::new(1),
        net: Arc::new(crate::netinfo::Simple),
    });
    std::thread::spawn(move || server::serve(listener, shared));
    let (tx, events) = mpsc::channel();
    let ours = Arc::new(Identity {
        keys: KeyPair::generate(),
        pc_id: [9; 16],
        name: "PC".into(),
    });
    let udp = Arc::new(UdpSocket::bind("127.0.0.1:0").unwrap());
    let hub = LinkHub::new(
        ours,
        routes,
        udp,
        Arc::new(AtomicBool::new(true)),
        move |e| {
            let _ = tx.send(e);
        },
    );
    owlmic_hub::spawn("link", hub, inbox);

    let mut phone = SimPhone::new(2);
    let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
    stream.write_all(&[CHANNEL_CONTROL]).unwrap();
    let mut w = Writer::new(stream.try_clone().unwrap());
    let mut r = Reader::new(stream, Arc::new(Mutex::new(None)));
    w.send_raw(kind::HELLO, &phone.hello(crate::LINK_USB_DEBUGGING, None))
        .unwrap();
    let LinkEvent::Hello { conn, .. } = next(&events, |e| matches!(e, LinkEvent::Hello { .. }))
    else {
        unreachable!()
    };
    to_hub.send(LinkMsg::Decided {
        conn,
        decision: Decision::Busy {
            owner: "Pixel 8".into(),
        },
    });
    assert_eq!(control(&mut r).0, kind::HELLO_ACK);
    let (k, reject) = control(&mut r);
    assert_eq!(k, kind::REJECT);
    assert_eq!(
        String::from_utf8(reject).unwrap(),
        r#"{"reason":"busy","owner":"Pixel 8"}"#
    );
}
