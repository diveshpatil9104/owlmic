//! Control frames and wrapped media on a byte stream (protocol/README.md, sections 3 and 4),
//! encrypted on wireless links once the session is up.

use owlmic_proto::crypto::{CONTROL_STREAM, Cipher};
use owlmic_proto::frame::{ControlHeader, MEDIA_MARKER};
use owlmic_proto::messages::Message;
use std::io::{self, Read, Write};
use std::sync::{Arc, Mutex};

pub enum Item {
    /// A control frame: its type and its (decrypted) payload.
    Control { kind: u8, payload: Vec<u8> },
    /// A media packet from a stream carrier.
    Media(Vec<u8>),
}

/// The receive cipher, set by the Link Hub right before WELCOME goes out, so the reader turns
/// on decryption exactly where the phone turns on encryption.
pub type CipherSlot = Arc<Mutex<Option<Cipher>>>;

pub struct Reader<R> {
    inner: R,
    cipher: CipherSlot,
    counter: u64,
}

impl<R: Read> Reader<R> {
    pub fn new(inner: R, cipher: CipherSlot) -> Self {
        Self {
            inner,
            cipher,
            counter: 0,
        }
    }

    pub fn read_item(&mut self) -> io::Result<Item> {
        let mut first = [0u8; 1];
        self.inner.read_exact(&mut first)?;
        if first[0] == MEDIA_MARKER {
            let mut len = [0u8; 2];
            self.inner.read_exact(&mut len)?;
            let mut packet = vec![0; u16::from_be_bytes(len) as usize];
            self.inner.read_exact(&mut packet)?;
            return Ok(Item::Media(packet));
        }
        let mut rest = [0u8; 3];
        self.inner.read_exact(&mut rest)?;
        let raw = [first[0], rest[0], rest[1], rest[2]];
        let header = ControlHeader::decode(raw)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "bad frame"))?;
        let mut payload = vec![0; header.len as usize];
        self.inner.read_exact(&mut payload)?;
        let cipher = self.cipher.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(c) = cipher.as_ref() {
            payload = c
                .open(CONTROL_STREAM, self.counter, &raw, &payload)
                .ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidData, "frame failed authentication")
                })?;
            self.counter += 1;
        }
        Ok(Item::Control {
            kind: header.kind,
            payload,
        })
    }
}

pub struct Writer<W> {
    inner: W,
    cipher: Option<Cipher>,
    counter: u64,
}

impl<W: Write> Writer<W> {
    pub fn new(inner: W) -> Self {
        Self {
            inner,
            cipher: None,
            counter: 0,
        }
    }

    /// From now on, control frames are sealed.
    pub fn encrypt_with(&mut self, cipher: Cipher) {
        self.cipher = Some(cipher);
        self.counter = 0;
    }

    pub fn send(&mut self, msg: &Message) -> io::Result<()> {
        self.send_raw(msg.kind(), &msg.to_payload())
    }

    /// Sends a payload already serialized, such as a HELLO_ACK whose bytes are in the transcript.
    pub fn send_raw(&mut self, kind: u8, payload: &[u8]) -> io::Result<()> {
        let frame = match &self.cipher {
            None => {
                let header = ControlHeader {
                    kind,
                    len: payload.len() as u32,
                }
                .encode();
                [header.as_slice(), payload].concat()
            }
            Some(c) => {
                let header = ControlHeader {
                    kind,
                    len: payload.len() as u32 + 16,
                }
                .encode();
                let sealed = c.seal(CONTROL_STREAM, self.counter, &header, payload);
                self.counter += 1;
                [header.as_slice(), &sealed].concat()
            }
        };
        self.inner.write_all(&frame)?;
        self.inner.flush()
    }

    pub fn media(&mut self, packet: &[u8]) -> io::Result<()> {
        let mut out = Vec::with_capacity(packet.len() + 3);
        owlmic_proto::frame::wrap_for_stream(packet, &mut out);
        self.inner.write_all(&out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use owlmic_proto::messages::Ping;

    #[test]
    fn frames_round_trip_in_the_clear_and_sealed() {
        let key = [7u8; 32];
        let mut w = Writer::new(Vec::new());
        w.send(&Message::Ping(Ping { t: 1 })).unwrap();
        w.media(&[1, 2, 3]).unwrap();
        w.encrypt_with(Cipher::new(&key));
        w.send(&Message::Ping(Ping { t: 2 })).unwrap();
        w.send(&Message::Ping(Ping { t: 3 })).unwrap();

        let slot: CipherSlot = Arc::new(Mutex::new(None));
        let mut r = Reader::new(w.inner.as_slice(), slot.clone());
        assert!(
            matches!(r.read_item().unwrap(), Item::Control { payload, .. } if payload == br#"{"t":1}"#)
        );
        assert!(matches!(r.read_item().unwrap(), Item::Media(p) if p == [1, 2, 3]));
        *slot.lock().unwrap() = Some(Cipher::new(&key));
        assert!(
            matches!(r.read_item().unwrap(), Item::Control { payload, .. } if payload == br#"{"t":2}"#)
        );
        assert!(
            matches!(r.read_item().unwrap(), Item::Control { payload, .. } if payload == br#"{"t":3}"#)
        );
    }

    #[test]
    fn a_tampered_sealed_frame_is_refused() {
        let mut w = Writer::new(Vec::new());
        w.encrypt_with(Cipher::new(&[1; 32]));
        w.send(&Message::Ping(Ping { t: 9 })).unwrap();
        let mut bytes = w.inner;
        let last = bytes.len() - 1;
        bytes[last] ^= 1;
        let mut r = Reader::new(
            bytes.as_slice(),
            Arc::new(Mutex::new(Some(Cipher::new(&[1; 32])))),
        );
        assert!(r.read_item().is_err());
    }
}
