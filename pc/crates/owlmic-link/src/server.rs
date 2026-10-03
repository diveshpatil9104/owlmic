//! TCP connections to the control port (protocol/README.md, section 3): the first byte says
//! whether it is a control channel or, through `adb reverse`, a media channel. Each connection
//! reads on its own thread and hands frames to the Link Hub.

use crate::hub::{Carrier, LinkMsg, Opened};
use crate::media::Routes;
use crate::netinfo::NetInfo;
use crate::wire::{CipherSlot, Item, Reader, Writer};
use owlmic_hub::Outbox;
use owlmic_proto::frame::{CHANNEL_CONTROL, CHANNEL_MEDIA, MediaHeader, stream};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub type SharedWriter = Arc<Mutex<Writer<Box<dyn Write + Send>>>>;
pub type Closer = Arc<dyn Fn() + Send + Sync>;

/// Nothing at all for this long ends a connection's thread; the heartbeat ends dead links sooner.
const READ_TIMEOUT: Duration = Duration::from_secs(30);
const WRITE_TIMEOUT: Duration = Duration::from_secs(5);
const FIRST_BYTE_TIMEOUT: Duration = Duration::from_secs(3);

pub struct Shared {
    pub routes: Arc<Routes>,
    pub to_hub: Outbox<LinkMsg>,
    pub next_conn: AtomicU64,
    pub net: Arc<dyn NetInfo>,
}

impl Shared {
    pub fn conn_id(&self) -> u64 {
        self.next_conn.fetch_add(1, Ordering::Relaxed)
    }
}

/// Accepts connections until the listener fails. Runs on its own thread.
pub fn serve(listener: TcpListener, shared: Arc<Shared>) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let shared = shared.clone();
        let _ = std::thread::Builder::new()
            .name("owlmic-conn".into())
            .spawn(move || accept(stream, shared));
    }
}

fn accept(mut stream: TcpStream, shared: Arc<Shared>) {
    let Ok(peer) = stream.peer_addr() else { return };
    let _ = stream.set_nodelay(true);
    let _ = stream.set_read_timeout(Some(FIRST_BYTE_TIMEOUT));
    let mut channel = [0u8; 1];
    if stream.read_exact(&mut channel).is_err() {
        return;
    }
    let _ = stream.set_read_timeout(Some(READ_TIMEOUT));
    let _ = stream.set_write_timeout(Some(WRITE_TIMEOUT));
    // Through adb reverse the phone always arrives from this PC itself.
    let link = if peer.ip().is_loopback() {
        crate::LINK_USB_DEBUGGING
    } else {
        shared.net.link_for_peer(peer.ip())
    };
    let (Ok(read_half), Ok(write_half), Ok(closer)) =
        (stream.try_clone(), stream.try_clone(), stream.try_clone())
    else {
        return;
    };
    let close: Closer = Arc::new(move || {
        let _ = closer.shutdown(std::net::Shutdown::Both);
    });
    match channel[0] {
        CHANNEL_CONTROL => run_control(read_half, Box::new(write_half), link, peer, close, &shared),
        CHANNEL_MEDIA => run_media_channel(read_half, Box::new(write_half), link, close, &shared),
        _ => close(),
    }
}

/// Runs one control connection until it closes. Also used for Bluetooth, where the same stream
/// carries the audio.
pub fn run_control(
    read: impl Read,
    write: Box<dyn Write + Send>,
    link: u8,
    peer: SocketAddr,
    close: Closer,
    shared: &Shared,
) {
    let conn = shared.conn_id();
    let writer: SharedWriter = Arc::new(Mutex::new(Writer::new(write)));
    let rx: CipherSlot = Arc::new(Mutex::new(None));
    shared.to_hub.send(LinkMsg::Opened(Opened {
        conn,
        link,
        peer,
        writer: writer.clone(),
        rx: rx.clone(),
        close,
    }));
    let mut reader = Reader::new(read, rx);
    loop {
        match reader.read_item() {
            Ok(Item::Control { kind, payload }) => shared.to_hub.send(LinkMsg::Frame {
                conn,
                kind,
                payload,
            }),
            Ok(Item::Media(packet)) => {
                let Some(h) = MediaHeader::decode(&packet) else {
                    continue;
                };
                if h.stream == stream::CARRIER_HELLO {
                    if let Some(session_id) =
                        shared.routes.carrier_hello(&packet[MediaHeader::LEN..])
                    {
                        let carrier = Carrier::Stream {
                            conn,
                            writer: writer.clone(),
                            link,
                        };
                        shared.to_hub.send(LinkMsg::CarrierUp {
                            carrier,
                            session_id,
                        });
                    }
                } else {
                    shared.routes.receive(&packet, crate::is_wireless(link));
                }
            }
            Err(_) => break,
        }
    }
    shared.to_hub.send(LinkMsg::Closed { conn });
}

/// The media channel through `adb reverse`: a carrier hello first, then media packets.
fn run_media_channel(
    read: impl Read,
    write: Box<dyn Write + Send>,
    link: u8,
    close: Closer,
    shared: &Shared,
) {
    let conn = shared.conn_id();
    let writer: SharedWriter = Arc::new(Mutex::new(Writer::new(write)));
    let mut reader = Reader::new(read, Arc::new(Mutex::new(None)));
    let Ok(Item::Media(hello)) = reader.read_item() else {
        return close();
    };
    let session = MediaHeader::decode(&hello)
        .filter(|h| h.stream == stream::CARRIER_HELLO)
        .and_then(|_| shared.routes.carrier_hello(&hello[MediaHeader::LEN..]));
    let Some(session_id) = session else {
        return close();
    };
    shared.to_hub.send(LinkMsg::CarrierUp {
        carrier: Carrier::Stream { conn, writer, link },
        session_id,
    });
    while let Ok(item) = reader.read_item() {
        if let Item::Media(packet) = item {
            shared.routes.receive(&packet, false);
        }
    }
    shared.to_hub.send(LinkMsg::CarrierDown { conn });
}
