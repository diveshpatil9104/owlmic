//! Startup (SYSTEM_DESIGN section 13.4, target: tray within 150 ms): the store, the hubs and the
//! tray come up on the main thread; sockets, Bluetooth and adb come up on a startup thread; the
//! device check runs on the Device Hub. Problems become a Repair tile, never a dialog.

use crate::app::{AppHub, AppMsg, DeviceCommand, Wiring};
use crate::sinks::{CameraSink, MicSink};
use owlmic_devices::dpapi::Dpapi;
use owlmic_devices::{DeviceEvent, DeviceHub, DeviceMsg, Shared as DeviceShared};
use owlmic_hub::{Outbox, Publisher, mailbox, spawn_supervised};
use owlmic_link::answerer::{Answerer, Me};
use owlmic_link::netinfo::{Adapters, NetInfo};
use owlmic_link::server::{self, Shared};
use owlmic_link::{LinkHub, LinkMsg, Routes};
use owlmic_media::audio::mic::MicReceiver;
use owlmic_media::audio::pipeline::JitterBuffer;
use owlmic_media::audio::speaker::SpeakerSender;
use owlmic_media::hub::{Media, MediaHub};
use owlmic_media::video::receiver::VideoReceiver;
use owlmic_proto::messages::Message;
use owlmic_session::{Identity, SessionHub, hex};
use owlmic_settings::hub::SettingsHub;
use owlmic_settings::store::Store;
use owlmic_ui::preview::Preview;
use owlmic_ui::view::PanelState;
use owlmic_ui::win::{Ui, Waker};
use std::net::{TcpListener, UdpSocket};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64};
use std::time::Duration;
use windows::Win32::Foundation::HANDLE;
use windows::Win32::NetworkManagement::IpHelper::{
    MIB_IPINTERFACE_ROW, MIB_NOTIFICATION_TYPE, NotifyIpInterfaceChange,
};
use windows::Win32::Networking::WinSock::AF_UNSPEC;
use windows::Win32::System::Com::{COINIT_APARTMENTTHREADED, CoInitializeEx};
use windows::Win32::UI::HiDpi::{
    DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, SetProcessDpiAwarenessContext,
};

const TCP_PORT: u16 = 7653;
const BEACON_PORT: u16 = 7654;
const MEDIA_PORT: u16 = 7655;

fn addresses() -> Vec<String> {
    Adapters::networks()
        .iter()
        .map(|n| n.address.to_string())
        .collect()
}

/// Runs Owlmic until Quit. `open` shows the panel once (a launch by hand, not at sign-in).
pub fn run(open: bool) {
    unsafe {
        let _ = SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
    }
    let Some(dir) = std::env::var_os("APPDATA").map(|a| PathBuf::from(a).join("Owlmic")) else {
        return;
    };
    let store = Arc::new(Store::open(dir.join("owlmic.json"), Box::new(Dpapi)));
    let identity = Arc::new(Identity::load_or_create(&store));

    let (app, app_in) = mailbox::<AppMsg>(512);
    let (link, link_in) = mailbox::<LinkMsg>(512);
    let (session, session_in) = mailbox(64);
    let (media, media_in) = mailbox(64);
    let (settings, settings_in) = mailbox(64);
    let (devices, devices_in) = mailbox::<DeviceMsg>(64);

    let waker = Waker::default();
    let w = waker.clone();
    let panel = Publisher::new(PanelState::default(), move || w.wake());
    let preview = Arc::new(Preview::default());

    let jitter = Arc::new(JitterBuffer::new());
    let mic = Arc::new(MicReceiver::new(jitter.clone()));
    let l = link.clone();
    let video = Arc::new(VideoReceiver::new(move || {
        l.send(LinkMsg::Send(Message::KeyframeRequest))
    }));
    let routes = Arc::new(Routes::new(
        Some(Arc::new(MicSink(mic.clone()))),
        Some(Arc::new(CameraSink(video.clone()))),
    ));
    let r = routes.clone();
    let speaker = Arc::new(SpeakerSender::new(Box::new(move |ts, payload| {
        r.send_speaker(ts, payload)
    })));

    {
        let (link, session, media, settings, devices, store, panel, waker) = (
            link.clone(),
            session.clone(),
            media.clone(),
            settings.clone(),
            devices.clone(),
            store.clone(),
            panel.clone(),
            waker.clone(),
        );
        spawn_supervised(
            "app",
            move || {
                let d = devices.clone();
                let w = waker.clone();
                let wiring = Wiring {
                    link: link.clone(),
                    session: session.clone(),
                    media: media.clone(),
                    settings: settings.clone(),
                    devices: Box::new(move |c| d.send(device_msg(c))),
                    autostart: Box::new(crate::autostart::sync),
                    quit: Box::new(move || w.quit()),
                };
                AppHub::new(wiring, store.clone(), panel.clone())
            },
            app_in,
        );
    }
    let a = app.clone();
    let s = store.clone();
    spawn_supervised(
        "session",
        move || {
            let a = a.clone();
            SessionHub::new(s.clone(), move |e| a.send(AppMsg::Session(e)))
        },
        session_in,
    );
    let a = app.clone();
    let s = store.clone();
    spawn_supervised(
        "settings",
        move || {
            let a = a.clone();
            SettingsHub::new(s.clone(), move |e| a.send(AppMsg::Settings(e)))
        },
        settings_in,
    );
    let a = app.clone();
    let m = (jitter.clone(), mic.clone(), speaker.clone(), video.clone());
    spawn_supervised(
        "media",
        move || {
            let a = a.clone();
            let media = Media {
                jitter: m.0.clone(),
                mic: m.1.clone(),
                speaker: m.2.clone(),
                video: m.3.clone(),
            };
            MediaHub::new(media, move |e| a.send(AppMsg::Media(e)))
        },
        media_in,
    );
    let a = app.clone();
    let me = devices.clone();
    let win11 = owlmic_vcam::register::is_windows_11();
    let d = (store.clone(), jitter, speaker, video, preview.clone());
    spawn_supervised(
        "devices",
        move || {
            let a = a.clone();
            let shared = DeviceShared {
                store: d.0.clone(),
                jitter: d.1.clone(),
                speaker: d.2.clone(),
                video: d.3.clone(),
                preview: d.4.clone(),
                win11,
            };
            DeviceHub::new(me.clone(), shared, move |e| match e {
                DeviceEvent::Health(h) => a.send(AppMsg::Health(h)),
                DeviceEvent::Repairing(r) => a.send(AppMsg::Repairing(r)),
            })
        },
        devices_in,
    );
    devices.send(DeviceMsg::Check);

    let (a, l, s) = (app.clone(), link.clone(), store.clone());
    let _ = std::thread::Builder::new()
        .name("startup".into())
        .spawn(move || network(identity, routes, s, l, link_in, a));

    let a = app.clone();
    let ui = Ui {
        state: panel,
        preview,
        on_action: Box::new(move |action| a.send(AppMsg::Ui(action))),
    };
    let _ = owlmic_ui::win::run(&waker, ui, open);

    // Quitting: the Device Hub unmutes the PC speakers before the process ends.
    let (done, wait) = std::sync::mpsc::sync_channel(1);
    devices.send(DeviceMsg::Shutdown(done));
    let _ = wait.recv_timeout(Duration::from_secs(2));
    std::process::exit(0);
}

fn device_msg(c: DeviceCommand) -> DeviceMsg {
    match c {
        DeviceCommand::Mic(on) => DeviceMsg::Mic(on),
        DeviceCommand::Speaker(on) => DeviceMsg::Speaker(on),
        DeviceCommand::Camera(on) => DeviceMsg::Camera(on),
        DeviceCommand::QuietPc(on) => DeviceMsg::QuietPc(on),
        DeviceCommand::Shape { framing, mirror } => DeviceMsg::Shape { framing, mirror },
        DeviceCommand::Repair => DeviceMsg::Repair,
    }
}

/// The Transporter's sockets and threads, then the Link Hub with the PC's Bluetooth address.
fn network(
    identity: Arc<Identity>,
    routes: Arc<Routes>,
    store: Arc<Store>,
    link: Outbox<LinkMsg>,
    link_in: owlmic_hub::Inbox<LinkMsg>,
    app: Outbox<AppMsg>,
) {
    let net: Arc<dyn NetInfo> = Arc::new(Adapters);
    let busy = Arc::new(AtomicBool::new(false));
    let me = Me {
        pc_id: identity.pc_id,
        key_hint: identity.key_hint(),
        name: identity.name.clone(),
        tcp_port: TCP_PORT,
        media_port: MEDIA_PORT,
    };
    let approved =
        move |id: &[u8; 16]| store.read(|d| d.phones.get(&hex(id)).is_some_and(|p| !p.blocked));
    let answerer = Answerer::bind(BEACON_PORT, me, busy.clone(), approved, net.clone())
        .ok()
        .map(Arc::new);
    let shared = Arc::new(Shared {
        routes: routes.clone(),
        to_hub: link.clone(),
        next_conn: AtomicU64::new(1),
        net,
    });
    if let Ok(listener) = TcpListener::bind(("0.0.0.0", TCP_PORT)) {
        let s = shared.clone();
        let _ = std::thread::Builder::new()
            .name("control listener".into())
            .spawn(move || server::serve(listener, s));
    }
    // A missing UDP socket (port taken) still leaves USB debugging and Bluetooth working.
    let udp = UdpSocket::bind(("0.0.0.0", MEDIA_PORT))
        .or_else(|_| UdpSocket::bind(("0.0.0.0", 0)))
        .map(Arc::new);
    let Ok(udp) = udp else { return };
    {
        let (u, r, l) = (udp.clone(), routes.clone(), link.clone());
        let _ = std::thread::Builder::new()
            .name("udp media".into())
            .spawn(move || owlmic_link::udp::run(u, r, l));
    }
    let bt_addr = owlmic_link::bt::start(shared);
    owlmic_link::adb::start();
    let mut hub = LinkHub::new(identity, routes, udp, busy, {
        let a = app.clone();
        move |e| a.send(AppMsg::Link(e))
    })
    .with_bt_addr(bt_addr);
    if let Some(ans) = &answerer {
        let a = ans.clone();
        hub = hub.with_announce(move || a.announce(BEACON_PORT));
    }
    // The Link Hub is built once: its connections live in sockets owned by other threads.
    owlmic_hub::spawn("link", hub, link_in);
    if let Some(ans) = answerer {
        ans.announce(BEACON_PORT);
        let _ = std::thread::Builder::new()
            .name("answerer".into())
            .spawn(move || ans.run());
    }
    app.send(AppMsg::Addresses(addresses()));
    watch_networks(link, app);
}

/// Joining or leaving a network: announce again, and refresh the addresses the settings show.
fn watch_networks(link: Outbox<LinkMsg>, app: Outbox<AppMsg>) {
    unsafe extern "system" fn changed(
        context: *const std::ffi::c_void,
        _: *const MIB_IPINTERFACE_ROW,
        _: MIB_NOTIFICATION_TYPE,
    ) {
        let (link, app) = unsafe { &*(context as *const (Outbox<LinkMsg>, Outbox<AppMsg>)) };
        link.send(LinkMsg::NetworkChanged);
        app.send(AppMsg::Addresses(addresses()));
    }
    // Lives as long as the app.
    let context: &'static (Outbox<LinkMsg>, Outbox<AppMsg>) = Box::leak(Box::new((link, app)));
    let mut handle = HANDLE::default();
    unsafe {
        let _ = NotifyIpInterfaceChange(
            AF_UNSPEC,
            Some(changed),
            Some((context as *const (Outbox<LinkMsg>, Outbox<AppMsg>)).cast()),
            false,
            &mut handle,
        );
    }
}
