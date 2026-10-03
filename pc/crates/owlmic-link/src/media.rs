//! The media path through the Transporter (SYSTEM_DESIGN section 10): carriers hand packets
//! straight to the pipelines and take the speaker's packets straight out. No hub sits in between;
//! the Link Hub only installs the session and picks the carrier.

use crate::monitor::StreamStats;
use owlmic_proto::crypto::{self, Cipher, Key, ReplayWindow, SessionKeys};
use owlmic_proto::frame::{MediaHeader, stream};
use std::collections::HashMap;
use std::io::Write;
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

/// A pipeline that takes one stream's packets.
pub trait MediaSink: Send + Sync {
    fn deliver(&self, header: &MediaHeader, payload: &[u8]);
}

/// Where outgoing media goes: the session's active carrier.
pub enum OutPath {
    Udp {
        socket: Arc<UdpSocket>,
        to: SocketAddr,
        encrypt: bool,
    },
    Stream {
        writer: Arc<Mutex<crate::wire::Writer<Box<dyn Write + Send>>>>,
        encrypt: bool,
    },
}

struct Session {
    id: [u8; 16],
    auth: Key,
    rx: Cipher,
    tx: Cipher,
    replay: [ReplayWindow; 4],
}

pub struct Routes {
    session: Mutex<Option<Session>>,
    out: Mutex<Option<OutPath>>,
    /// Peers with a live control link, and whether their link is wireless (so encrypted).
    peers: Mutex<HashMap<IpAddr, bool>>,
    mic: Option<Arc<dyn MediaSink>>,
    camera: Option<Arc<dyn MediaSink>>,
    pub stats: [StreamStats; 4],
    speaker_seq: AtomicU32,
    clock: Instant,
}

impl Routes {
    pub fn new(mic: Option<Arc<dyn MediaSink>>, camera: Option<Arc<dyn MediaSink>>) -> Self {
        Self {
            session: Mutex::new(None),
            out: Mutex::new(None),
            peers: Mutex::new(HashMap::new()),
            mic,
            camera,
            stats: Default::default(),
            speaker_seq: AtomicU32::new(1),
            clock: Instant::now(),
        }
    }

    /// Microseconds since the routes were made: the PC's media clock.
    pub fn now_us(&self) -> u64 {
        self.clock.elapsed().as_micros() as u64
    }

    pub fn install(&self, session_id: [u8; 16], keys: &SessionKeys) {
        *self.lock_session() = Some(Session {
            id: session_id,
            auth: keys.auth,
            rx: Cipher::new(&keys.phone_to_pc),
            tx: Cipher::new(&keys.pc_to_phone),
            replay: Default::default(),
        });
        for s in &self.stats {
            s.reset();
        }
    }

    pub fn clear(&self) {
        *self.lock_session() = None;
        *self.lock_out() = None;
        self.peers.lock().unwrap_or_else(|p| p.into_inner()).clear();
    }

    /// A peer whose control link is up may send datagrams; `wireless` decides encryption.
    pub fn set_peer(&self, ip: IpAddr, wireless: bool) {
        self.peers
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(ip, wireless);
    }

    /// Whether datagrams from `ip` are encrypted, or `None` for a peer without a session.
    pub fn peer(&self, ip: IpAddr) -> Option<bool> {
        self.peers
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&ip)
            .copied()
    }

    pub fn session_id(&self) -> Option<[u8; 16]> {
        self.lock_session().as_ref().map(|s| s.id)
    }

    /// Checks a carrier hello (stream 0): the session it claims, proven with the session's key.
    pub fn carrier_hello(&self, payload: &[u8]) -> Option<[u8; 16]> {
        let session = self.lock_session();
        let s = session.as_ref()?;
        let (id, mac) = payload.split_at_checked(16)?;
        (id == s.id && mac == crypto::carrier_mac(&s.auth, &s.id)).then_some(s.id)
    }

    /// Moves outgoing media to `out`, between two packets.
    pub fn set_out(&self, out: Option<OutPath>) {
        *self.lock_out() = out;
    }

    /// One media packet from a carrier. `encrypted` says whether this carrier's link is wireless.
    pub fn receive(&self, packet: &[u8], encrypted: bool) {
        let Some(header) = MediaHeader::decode(packet) else {
            return;
        };
        if header.stream == stream::CARRIER_HELLO || header.stream > stream::SPEAKER {
            return;
        }
        let body = &packet[MediaHeader::LEN..];
        let decrypted;
        let payload: &[u8] = {
            let mut session = self.lock_session();
            let Some(s) = session.as_mut() else { return };
            let p: &[u8] = if encrypted {
                match s.rx.open(
                    header.stream,
                    header.seq as u64,
                    &packet[..MediaHeader::LEN],
                    body,
                ) {
                    Some(p) => {
                        decrypted = p;
                        &decrypted
                    }
                    None => return,
                }
            } else {
                body
            };
            if !s.replay[header.stream as usize].accept(header.seq) {
                return;
            }
            p
        };
        self.stats[header.stream as usize].packet(
            header.seq,
            header.timestamp_us,
            self.now_us(),
            packet.len(),
        );
        let sink = match header.stream {
            stream::MIC => &self.mic,
            stream::CAMERA => &self.camera,
            _ => &None,
        };
        if let Some(sink) = sink {
            sink.deliver(&header, payload);
        }
    }

    /// Sends one speaker packet on the active carrier. Dropped when there is none.
    pub fn send_speaker(&self, timestamp_us: u32, payload: &[u8]) {
        let header = MediaHeader {
            stream: stream::SPEAKER,
            keyframe: false,
            seq: self.speaker_seq.fetch_add(1, Ordering::Relaxed),
            timestamp_us,
        };
        let head = header.encode();
        let out = self.lock_out();
        let Some(out) = out.as_ref() else { return };
        let encrypt = match out {
            OutPath::Udp { encrypt, .. } | OutPath::Stream { encrypt, .. } => *encrypt,
        };
        let body = if encrypt {
            let session = self.lock_session();
            let Some(s) = session.as_ref() else { return };
            s.tx.seal(header.stream, header.seq as u64, &head, payload)
        } else {
            payload.to_vec()
        };
        let packet = [head.as_slice(), &body].concat();
        let _ = match out {
            OutPath::Udp { socket, to, .. } => socket.send_to(&packet, to).map(|_| ()),
            OutPath::Stream { writer, .. } => writer
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .media(&packet),
        };
    }

    fn lock_session(&self) -> std::sync::MutexGuard<'_, Option<Session>> {
        self.session.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn lock_out(&self) -> std::sync::MutexGuard<'_, Option<OutPath>> {
        self.out.lock().unwrap_or_else(|p| p.into_inner())
    }
}

/// A carrier hello packet, as the phone sends it (and tests do).
pub fn carrier_hello_packet(session_id: [u8; 16], auth: &Key) -> Vec<u8> {
    let header = MediaHeader {
        stream: stream::CARRIER_HELLO,
        keyframe: false,
        seq: 0,
        timestamp_us: 0,
    };
    [
        header.encode().as_slice(),
        &session_id,
        &crypto::carrier_mac(auth, &session_id),
    ]
    .concat()
}

#[cfg(test)]
mod tests {
    use super::*;
    use owlmic_proto::crypto::session_keys;

    struct Collect(Mutex<Vec<(u32, Vec<u8>)>>);
    impl MediaSink for Collect {
        fn deliver(&self, h: &MediaHeader, p: &[u8]) {
            self.0.lock().unwrap().push((h.seq, p.to_vec()));
        }
    }

    fn sealed(keys: &SessionKeys, stream: u8, seq: u32, payload: &[u8]) -> Vec<u8> {
        let h = MediaHeader {
            stream,
            keyframe: false,
            seq,
            timestamp_us: seq * 10_000,
        }
        .encode();
        [
            h.as_slice(),
            &Cipher::new(&keys.phone_to_pc).seal(stream, seq as u64, &h, payload),
        ]
        .concat()
    }

    #[test]
    fn sealed_mic_packets_reach_the_pipeline_once_and_forgeries_never() {
        let mic = Arc::new(Collect(Mutex::new(Vec::new())));
        let routes = Routes::new(Some(mic.clone()), None);
        let keys = session_keys(&[4; 32]);
        routes.install([1; 16], &keys);
        routes.receive(&sealed(&keys, stream::MIC, 1, b"a"), true);
        routes.receive(&sealed(&keys, stream::MIC, 1, b"a"), true);
        let mut forged = sealed(&keys, stream::MIC, 2, b"b");
        *forged.last_mut().unwrap() ^= 1;
        routes.receive(&forged, true);
        routes.receive(&sealed(&keys, stream::MIC, 3, b"c"), true);
        assert_eq!(
            *mic.0.lock().unwrap(),
            vec![(1, b"a".to_vec()), (3, b"c".to_vec())]
        );
        assert_eq!(routes.stats[1].packets.load(Ordering::Relaxed), 2);
    }

    #[test]
    fn only_the_sessions_own_carrier_hello_is_accepted() {
        let routes = Routes::new(None, None);
        let keys = session_keys(&[5; 32]);
        routes.install([2; 16], &keys);
        let good = carrier_hello_packet([2; 16], &keys.auth);
        assert_eq!(
            routes.carrier_hello(&good[MediaHeader::LEN..]),
            Some([2; 16])
        );
        let bad = carrier_hello_packet([2; 16], &[0; 32]);
        assert_eq!(routes.carrier_hello(&bad[MediaHeader::LEN..]), None);
    }

    #[test]
    fn speaker_packets_go_out_sealed_on_the_active_carrier() {
        let routes = Routes::new(None, None);
        let keys = session_keys(&[6; 32]);
        routes.install([3; 16], &keys);
        let phone = UdpSocket::bind("127.0.0.1:0").unwrap();
        phone
            .set_read_timeout(Some(std::time::Duration::from_secs(2)))
            .unwrap();
        let pc = Arc::new(UdpSocket::bind("127.0.0.1:0").unwrap());
        routes.send_speaker(0, b"lost: no carrier yet");
        routes.set_out(Some(OutPath::Udp {
            socket: pc,
            to: phone.local_addr().unwrap(),
            encrypt: true,
        }));
        routes.send_speaker(480_000, b"pcm");
        let mut buf = [0u8; 256];
        let n = phone.recv(&mut buf).unwrap();
        let h = MediaHeader::decode(&buf[..n]).unwrap();
        assert_eq!(h.stream, stream::SPEAKER);
        let plain =
            Cipher::new(&keys.pc_to_phone).open(h.stream, h.seq as u64, &buf[..10], &buf[10..n]);
        assert_eq!(plain.as_deref(), Some(b"pcm".as_slice()));
    }
}
