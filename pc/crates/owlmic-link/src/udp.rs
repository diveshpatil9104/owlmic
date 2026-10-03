//! The datagram carrier on UDP 7655 (protocol/README.md, section 6): a carrier hello proves which
//! session a phone's address belongs to; after that its media goes straight to the pipelines.

use crate::hub::{Carrier, LinkMsg};
use crate::media::Routes;
use owlmic_hub::Outbox;
use owlmic_proto::frame::{MediaHeader, stream};
use std::net::UdpSocket;
use std::sync::Arc;

/// Receives media until the socket fails. Runs on its own thread.
pub fn run(socket: Arc<UdpSocket>, routes: Arc<Routes>, to_hub: Outbox<LinkMsg>) {
    let mut buf = vec![0u8; 2048];
    loop {
        let (n, from) = match socket.recv_from(&mut buf) {
            Ok(r) => r,
            // Windows reports an earlier send's ICMP "port unreachable" here; it isn't fatal.
            Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => continue,
            Err(_) => return,
        };
        let packet = &buf[..n];
        let Some(h) = MediaHeader::decode(packet) else {
            continue;
        };
        let Some(encrypted) = routes.peer(from.ip()) else {
            continue;
        };
        if h.stream == stream::CARRIER_HELLO {
            if let Some(session_id) = routes.carrier_hello(&packet[MediaHeader::LEN..]) {
                to_hub.send(LinkMsg::CarrierUp {
                    carrier: Carrier::Udp(from),
                    session_id,
                });
            }
        } else {
            routes.receive(packet, encrypted);
        }
    }
}
