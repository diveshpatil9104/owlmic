//! Bluetooth (SYSTEM_DESIGN section 14.1, level 4): an RFCOMM server that publishes the Owlmic
//! service record, so a bonded phone finds it by UUID. One stream carries control and audio.

use crate::server::{self, Closer, Shared};
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use windows::Win32::Devices::Bluetooth::{AF_BTH, BTHPROTO_RFCOMM, NS_BTH, SOCKADDR_BTH};
use windows::Win32::Networking::WinSock::{
    CSADDR_INFO, RNRSERVICE_REGISTER, SD_BOTH, SEND_RECV_FLAGS, SOCK_STREAM, SOCKADDR, SOCKET,
    SOCKET_ADDRESS, WSADATA, WSAQUERYSETW, WSASetServiceW, WSAStartup, accept, bind, getsockname,
    listen, recv, send, shutdown, socket,
};
use windows::core::{GUID, PWSTR};

/// "owlmic" in its first six bytes (SYSTEM_DESIGN section 14.1).
pub const SERVICE: GUID = GUID::from_u128(0x6f776c6d_6963_4000_8000_00805f9b34fb);
const BT_PORT_ANY: u32 = u32::MAX;

struct Socket(SOCKET);

impl Read for &Socket {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = unsafe { recv(self.0, buf, SEND_RECV_FLAGS(0)) };
        if n < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(n as usize)
        }
    }
}

impl Write for &Socket {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = unsafe { send(self.0, buf, SEND_RECV_FLAGS(0)) };
        if n < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(n as usize)
        }
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// The local radio's address as "AA:BB:CC:DD:EE:FF", for HELLO_ACK.
pub fn format_addr(addr: u64) -> String {
    (0..6)
        .rev()
        .map(|i| format!("{:02X}", (addr >> (i * 8)) & 0xFF))
        .collect::<Vec<_>>()
        .join(":")
}

/// Starts the RFCOMM server. Returns this PC's Bluetooth address, or `None` without a radio.
pub fn start(shared: Arc<Shared>) -> Option<String> {
    unsafe {
        let mut data = WSADATA::default();
        WSAStartup(0x0202, &mut data);
        let listener = socket(AF_BTH as i32, SOCK_STREAM, BTHPROTO_RFCOMM as i32).ok()?;
        let mut local = SOCKADDR_BTH {
            addressFamily: AF_BTH,
            btAddr: 0,
            serviceClassId: GUID::zeroed(),
            port: BT_PORT_ANY,
        };
        let len = size_of::<SOCKADDR_BTH>() as i32;
        if bind(listener, &local as *const _ as *const SOCKADDR, len) != 0
            || listen(listener, 2) != 0
        {
            return None;
        }
        let mut got = len;
        getsockname(listener, &mut local as *mut _ as *mut SOCKADDR, &mut got);
        if !publish(&mut local) {
            return None;
        }
        let addr = format_addr(local.btAddr);
        let sock = listener.0;
        let _ = std::thread::Builder::new()
            .name("owlmic-bt".into())
            .spawn(move || serve(SOCKET(sock), shared));
        Some(addr)
    }
}

/// Registers the service record so phones find this server's channel by the Owlmic UUID.
unsafe fn publish(local: &mut SOCKADDR_BTH) -> bool {
    let mut name: Vec<u16> = "Owlmic\0".encode_utf16().collect();
    let mut class = SERVICE;
    let mut addr = CSADDR_INFO {
        LocalAddr: SOCKET_ADDRESS {
            lpSockaddr: local as *mut _ as *mut SOCKADDR,
            iSockaddrLength: size_of::<SOCKADDR_BTH>() as i32,
        },
        RemoteAddr: SOCKET_ADDRESS::default(),
        iSocketType: SOCK_STREAM.0,
        iProtocol: BTHPROTO_RFCOMM as i32,
    };
    let set = WSAQUERYSETW {
        dwSize: size_of::<WSAQUERYSETW>() as u32,
        lpszServiceInstanceName: PWSTR(name.as_mut_ptr()),
        lpServiceClassId: &mut class,
        dwNameSpace: NS_BTH,
        dwNumberOfCsAddrs: 1,
        lpcsaBuffer: &mut addr,
        ..Default::default()
    };
    unsafe { WSASetServiceW(&set, RNRSERVICE_REGISTER, 0) == 0 }
}

fn serve(listener: SOCKET, shared: Arc<Shared>) {
    loop {
        let mut peer = SOCKADDR_BTH::default();
        let mut len = size_of::<SOCKADDR_BTH>() as i32;
        let Ok(s) = (unsafe {
            accept(
                listener,
                Some(&mut peer as *mut _ as *mut SOCKADDR),
                Some(&mut len),
            )
        }) else {
            return;
        };
        let shared = shared.clone();
        let _ = std::thread::Builder::new()
            .name("owlmic-bt-conn".into())
            .spawn(move || {
                let socket: &'static Socket = Box::leak(Box::new(Socket(s)));
                let close: Closer = Arc::new(move || unsafe {
                    shutdown(socket.0, SD_BOTH);
                });
                // Bluetooth has no IP address; a stable stand-in keeps per-peer limits working.
                let bytes = peer.btAddr.to_be_bytes();
                let ip = Ipv6Addr::new(
                    0xfd00,
                    0x6f77,
                    0,
                    0,
                    u16::from_be_bytes([bytes[2], bytes[3]]),
                    u16::from_be_bytes([bytes[4], bytes[5]]),
                    u16::from_be_bytes([bytes[6], bytes[7]]),
                    0,
                );
                server::run_control(
                    socket,
                    Box::new(socket),
                    crate::LINK_BLUETOOTH,
                    SocketAddr::new(IpAddr::V6(ip), 0),
                    close,
                    &shared,
                );
            });
    }
}
