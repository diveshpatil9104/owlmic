//! Discovery, PC side (SYSTEM_DESIGN section 14.2): answers each probe at once, and announces
//! itself three times when it starts or a network changes. Costs nothing between probes.

use crate::netinfo::NetInfo;
use crate::ratelimit::{PROBES_PER_SECOND, TokenBucket};
use owlmic_proto::discovery::{Answer, Probe};
use std::net::{SocketAddr, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

pub struct Me {
    pub pc_id: [u8; 16],
    pub key_hint: [u8; 8],
    pub name: String,
    pub tcp_port: u16,
    pub media_port: u16,
}

/// Whether a phone (by id) is approved on this PC.
pub type IsApproved = Box<dyn Fn(&[u8; 16]) -> bool + Send + Sync>;

pub struct Answerer {
    socket: UdpSocket,
    me: Me,
    busy: Arc<AtomicBool>,
    approved: IsApproved,
    net: Arc<dyn NetInfo>,
}

impl Answerer {
    pub fn bind(
        port: u16,
        me: Me,
        busy: Arc<AtomicBool>,
        approved: impl Fn(&[u8; 16]) -> bool + Send + Sync + 'static,
        net: Arc<dyn NetInfo>,
    ) -> std::io::Result<Self> {
        let socket = UdpSocket::bind(("0.0.0.0", port))?;
        socket.set_broadcast(true)?;
        Ok(Self {
            socket,
            me,
            busy,
            approved: Box::new(approved),
            net,
        })
    }

    pub fn local_port(&self) -> u16 {
        self.socket.local_addr().map_or(0, |a| a.port())
    }

    fn answer(&self, phone_id: Option<&[u8; 16]>, link: u8) -> Vec<u8> {
        Answer {
            pc_id: self.me.pc_id,
            key_hint: self.me.key_hint,
            tcp_port: self.me.tcp_port,
            media_port: self.me.media_port,
            proto: owlmic_proto::VERSION,
            busy: self.busy.load(Ordering::Relaxed),
            approval_required: phone_id.is_some_and(|id| !(self.approved)(id)),
            link,
            name: self.me.name.clone(),
        }
        .encode()
    }

    /// Three announcements, a moment apart, so a phone that is already searching hears one.
    pub fn announce(&self, port: u16) {
        let packet = self.answer(None, crate::LINK_WIFI);
        for i in 0..3 {
            for to in self.net.broadcasts(port) {
                let _ = self.socket.send_to(&packet, to);
            }
            if i < 2 {
                std::thread::sleep(Duration::from_millis(100));
            }
        }
    }

    /// Answers probes until the socket fails. Runs on its own thread; the answerer is shared so
    /// the Link Hub can announce with it too.
    pub fn run(&self) {
        let mut bucket = TokenBucket::new(PROBES_PER_SECOND, Instant::now());
        let mut buf = [0u8; 512];
        loop {
            let (n, from) = match self.socket.recv_from(&mut buf) {
                Ok(r) => r,
                Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => continue,
                Err(_) => return,
            };
            let Some(probe) = Probe::decode(&buf[..n]) else {
                continue;
            };
            if !bucket.allow(Instant::now()) {
                continue;
            }
            let link = self.net.link_for_peer(from.ip());
            let _ = self
                .socket
                .send_to(&self.answer(Some(&probe.phone_id), link), from);
        }
    }

    pub fn try_clone_socket(&self) -> std::io::Result<UdpSocket> {
        self.socket.try_clone()
    }
}

/// Sends a probe, as a phone does: for tests and the development tools.
pub fn probe(
    socket: &UdpSocket,
    to: SocketAddr,
    phone_id: [u8; 16],
    name: &str,
) -> std::io::Result<()> {
    socket
        .send_to(
            &Probe {
                phone_id,
                name: name.into(),
            }
            .encode(),
            to,
        )
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_probe_gets_an_answer_saying_who_and_whether_approval_is_needed() {
        let busy = Arc::new(AtomicBool::new(false));
        let me = Me {
            pc_id: [2; 16],
            key_hint: [3; 8],
            name: "DESKTOP-A".into(),
            tcp_port: 7653,
            media_port: 7655,
        };
        let a = Answerer::bind(
            0,
            me,
            busy.clone(),
            |id| *id == [1; 16],
            Arc::new(crate::netinfo::Simple),
        )
        .unwrap();
        let port = a.local_port();
        std::thread::spawn(move || a.run());
        let phone = UdpSocket::bind("127.0.0.1:0").unwrap();
        phone
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let mut buf = [0u8; 512];
        for (id, approval) in [([1u8; 16], false), ([9u8; 16], true)] {
            probe(&phone, ([127, 0, 0, 1], port).into(), id, "Pixel 8").unwrap();
            let n = phone.recv(&mut buf).unwrap();
            let answer = Answer::decode(&buf[..n]).unwrap();
            assert_eq!(answer.name, "DESKTOP-A");
            assert_eq!(answer.pc_id, [2; 16]);
            assert_eq!(answer.media_port, 7655);
            assert_eq!(answer.approval_required, approval);
            assert!(!answer.busy);
        }
        busy.store(true, Ordering::Relaxed);
        probe(&phone, ([127, 0, 0, 1], port).into(), [1; 16], "Pixel 8").unwrap();
        let n = phone.recv(&mut buf).unwrap();
        assert!(Answer::decode(&buf[..n]).unwrap().busy);
    }
}
