# Owlmic: Project Idea

> **Fast. Quick. Lightweight. Seamless.**

Owlmic is an open-source app that turns an Android phone into a Windows PC's **microphone, camera and speaker**.

This document is the knowledge base for the project. It describes the idea, the people it serves, how it should behave and look, and the modules it is made of. It states requirements and the decisions already made; the detailed technical design lives in the documentation.

---

## 1. The Idea

Many PCs and laptops have a poor microphone or webcam, or none at all, and sometimes the speakers stop working. Almost everyone already carries a phone with better hardware than most computers.

Owlmic lets that phone stand in for the PC's hardware:

- **Mic:** the phone's microphone becomes the PC's microphone.
- **Camera:** the phone's camera becomes the PC's webcam.
- **Speaker:** the PC's sound plays through the phone.

Once installed, any PC app (Zoom, Meet, Teams, Discord, OBS and so on) sees the phone's mic and camera as normal hardware, and the phone can play whatever the PC is playing.

**The guiding thought:** Owlmic does the difficult work internally so that the user's mental model stays extremely simple.

> **First time = setup + trust. Every time after that = open → connect → use.**

---

## 2. Who It Is For

- **Students and remote workers** with a broken or weak mic or webcam who cannot afford new hardware.
- **People in a hurry** whose call starts in a minute.
- **Non-technical users** who want one clear answer: it is on, or it is off.
- **Anyone whose PC audio or camera has stopped working.**

---

## 3. What Makes Owlmic Different

Similar apps exist (AudioRelay, WO Mic and others). Owlmic does not compete on the number of features. It competes on how fast and effortless it feels.

| Quality | What it means |
| :--- | :--- |
| **Fast** | Connected within about a second of opening the app, where technically achievable. |
| **Lightweight** | A PC installer of about 4 MB or less, and almost no RAM or CPU while idle. |
| **Invisible** | Running in the background never interferes with other programs. |
| **Seamless** | The user never feels lag, glitches or that something went wrong. |
| **Stable** | When something breaks, Owlmic recovers by itself. |
| **Convenient** | As few taps and clicks as possible, for setup and for every change. |

---

## 4. Core Principles

1. **Speed above all.** If a step can be removed, remove it.
2. **Hidden complexity.** Monitoring, switching and recovery happen behind the scenes.
3. **Self-healing.** Detect → recover → switch or fall back → continue. Ask the user only when automatic recovery is impossible.
4. **Minimal dependencies.** Rely on as little external software as possible. The long-term aim is Owlmic's own virtual drivers.
5. **Everything installed in one go.** The user never hunts for extra downloads.
6. **Permission only with a reason.** Never ask for a permission until the user uses the feature that needs it.
7. **Do not process for the sake of it.** Nothing is added to the media path unless it clearly helps.
8. **Calm interface.** Nothing flashy, nothing unnecessary.
9. **Privacy by design.** No accounts, no cloud, no telemetry, no logs.
10. **Honest state.** The app never claims something is working when it is not.

---

## 5. Scope

| In scope | Not now |
| :--- | :--- |
| Android phone app (Android 8.0 and newer) | iOS and macOS |
| Windows tray app (Windows 10 and 11) | Linux (on hold; may return later) |
| Mic, camera and speaker | Streaming over the internet (local links only) |
| Documentation | |
| Landing page | |

The project is not organised into "phases". This version is a **complete rewrite**. The existing app is a reference for lessons learned, not a base to build on.

---

## 6. Project Areas

Owlmic has four areas:

1. **Android app:** the phone side.
2. **Windows app:** the tray app and its supporting components.
3. **Documentation:** user and technical documentation.
4. **Landing page:** the public website.

The running product is only **Android ↔ Transporter ↔ Windows**. The landing page and documentation are part of the project but not part of the runtime, so the product itself stays lightweight.

---

## 7. The Experience

### 7.1 Phone

- Opens **straight to one fixed main screen**. No splash screen, no setup screen, no connection screen. The layout never changes.
- Three clear controls: **Mic**, **Camera** and **Speaker**, plus the connection status on the same screen.
- Controls appear **dimmed** until a PC is connected, then become usable.
- The app starts looking for an Owlmic PC the moment it opens.
- **Settings** sit one step away from the main screen.
- **Keeps working in the background.** With the screen off or the app in the background, active features keep running and a notification shows what is on. When the app is closed, everything stops. This must follow Play Store policy.

### 7.2 PC

- A **tray icon** that starts with Windows and is always present.
- Clicking it opens a **small rounded panel** (roughly 400 × 500 px) just above the tray, a tiny control panel rather than a traditional Windows menu.
- **No dialogs after install.** Apart from the one-time approval of a new phone, the PC never interrupts the user.
- The panel shows connection status, the connected phone, what is active, and a few essential controls.
- **Owlmic Mic** and **Owlmic Cam** appear in other apps as a normal microphone and camera.
- **The devices are always present,** even with no phone connected. In that case the mic gives silence and the camera gives a blank placeholder, so other apps never lose the device.

### 7.3 Permissions

The two sides deliberately use different strategies:

- **Phone: just in time.** Nothing is requested at launch. The microphone permission is asked on the first tap of Mic, the camera permission on the first tap of Camera, and so on.
- **PC: all at install.** The installer handles everything, so the user is never interrupted later.

**Permission and state are separate.** A permission means Owlmic *may* use the hardware. The toggle means Owlmic *is* using it. Granting a permission never switches a feature on.

---

## 8. User Flows

### 8.1 First-time setup

- **PC:** run one installer. It installs the tray app, every package and driver Owlmic needs, configures the required Windows settings and permissions, and enables start with Windows. Afterwards Owlmic is always in the tray.
- **Phone:** install the app and open it. That is all.

### 8.2 First connection (trust)

1. The PC is running; the user opens Owlmic on the phone.
2. The phone discovers the PC and sends a connection request.
3. **The PC asks the user to approve the phone.**
4. The user approves; the tray shows the phone as connected.
5. Mic, Camera and Speaker become usable on the phone.

Approval is a one-time security step that creates a trust relationship between that phone and that PC.

### 8.3 Every connection after that

1. The PC starts; the tray app starts with it.
2. The user opens the phone app.
3. The known PC is found, trust is recognised, and the connection happens automatically.
4. The user taps Mic, Camera or Speaker and it works.

No approval, no pairing, no dialogs: **open app → tap → working**.

### 8.4 When something goes wrong

1. Owlmic notices the problem itself (a dropped link, a stalled stream, choppy audio).
2. It tries to recover on the current link.
3. If that fails, it moves to the next best link.
4. The session continues. The user does nothing.
5. Only if recovery is truly impossible does the user see a short, plain message saying what happened and what to try.

The user must never have to switch Mic or Camera off and on to fix a stream.

---

## 9. Modules

```
   PHONE (Android app)                              PC (Windows tray app)
 ┌──────────────────────┐                        ┌──────────────────────┐
 │ Mic  Camera  Speaker │                        │ Tray panel           │
 │ Settings             │                        │ Approve gate         │
 └──────────┬───────────┘                        │ Virtual devices      │
            │                                    └──────────┬───────────┘
            │        ┌──────────────────────────┐           │
            ├───────►│       TRANSPORTER        │◄──────────┤   control path
            │        │ (manages the connection) │           │
            │        └──────────────────────────┘           │
            │                                               │
            └────────────── direct media ───────────────────┘   media path
```

**Two paths, two jobs:**

- **Control path (the Transporter):** who connects, over which link, when to switch, when to recover.
- **Media path:** carries the audio and video as directly and quickly as possible.

The Transporter manages the connection. It must not become the place where media is processed.

**Structure.** Each side is built as a clear hierarchy: one top hub, with sub-hubs beneath it (connection, media, settings) that own and monitor their modules. Work flows through it in one direction, and every module has a single job. **The PC is the authority** for session state; the phone follows it.

### 9.1 Transporter

The Transporter is a set of small, lightweight modules, not one large component. Its flow:

**Discovery → Handshake and priority → Monitoring → Link selection → Switching → Reconnection**

| Module | Job |
| :--- | :--- |
| **Beacon updater** | The PC announces that it is available so phones can find it instantly. |
| **Handshake and device priority** | Connects the right phone to the right PC over the best available link. |
| **Signal updater** | Phone and PC both watch connection quality and share it with each other. |
| **Signal switcher** | Moves the session to a better link when one appears. |
| **Signal security and ownership** | Makes sure only the approved, connected phone and PC exchange media. |
| **Reconnector** | Detects a broken or stalled stream and restores it. |

**Link priority**

| Priority | Link | Why |
| :---: | :--- | :--- |
| **1** | USB debugging (cable) | Best quality, lowest delay |
| **2** | USB tethering (cable) | Nearly as good |
| **3** | Wi-Fi | Wireless and convenient. Covers a shared network, the phone's hotspot and Wi-Fi Direct. |
| **4** | Bluetooth | Last resort; works almost anywhere. **Audio only:** the camera is unavailable on this link. |

USB debugging uses Android's standard debugging tool, which is bundled inside the PC installer.

**Discovery.** The PC emits a small beacon every second or two. The phone picks it up as soon as the app opens and the handshake begins by itself.

**Multiple PCs and phones.** A workspace may hold several of each, so the phone must never simply join the first PC it sees.
- Recently used devices get higher priority.
- Rejected or unapproved devices move down the list rather than being blocked forever.
- The phone has enough information to identify the intended PC.
- **One phone per PC.** Once a PC is claimed by one phone, no other phone can claim it at the same time.

**Pairing management.** The PC holds the authoritative list and is the only place phones are approved, rejected or blocked. The phone keeps its own simple list of known PCs. Managing pairings must never become part of the normal connection flow.

**Monitoring.** Both sides watch delay, throughput, packet activity and overall link health, in both directions, at two speeds: a quick heartbeat about once a second to catch a stalled stream fast, and a fuller quality report every 10 to 15 seconds. It must cost almost nothing in RAM and CPU, and it stays in memory only.

**Switching.** If a better link appears mid-call (for example a cable is plugged in while on Wi-Fi), Owlmic moves to it with **no dialog, no notification, no button and no audible or visible interruption**. Both links may be open briefly during the switch so that nothing drops. A new link must be stable for a moment first, to avoid flipping back and forth.

**Reconnection.** The current version has a known fault: the mic can stop and never come back. The new Reconnector must notice when media stops flowing even though the connection looks alive, and restore it without the user.

**Security.** Only the intended phone and PC ever receive the stream. Wireless links (Wi-Fi and Bluetooth) are encrypted with keys created at the first approval, using a method light enough to have no noticeable cost. Cable links only verify identity. Protection must never become a bottleneck for delay or throughput.

### 9.2 Media

| Module | Job |
| :--- | :--- |
| **Audio** | Voice quality, mono. |
| **Noise reduction** | Removes background noise from the voice. |
| **Echo cancellation** | Stops PC sound played on the phone speaker from being picked up by the phone mic and sent back. It should also keep the user's own voice clear when voices overlap. |
| **Audio smoother / video smoother** | Two separate pipelines that keep sound and picture steady when packet timing varies, without adding heavy buffering or delay. |
| **Compression** | Video is always compressed on wireless links, because it cannot fit otherwise. For audio and for cable links it is conditional: used only when the bandwidth saved is worth more than the time it costs. |
| **Camera** | 720p at 24 fps by default, up to 1080p at 60 fps. Front and back cameras, portrait and landscape. The phone controls what is **sent** (quality, frame rate, capture options). The PC controls what is **shown** (crop, aspect ratio), in real time. |
| **Speaker** | The phone plays whatever the PC is playing. This needs no extra driver, so there is no separate speaker device to select on the PC. |

The aim of the whole media path: move as much useful audio and video as possible, as quickly as possible.

**Where processing runs.** Noise reduction and echo cancellation run on the phone first, using what Android already provides, as long as that is fast and good enough. The PC adds its own stage only where it clearly improves the result.

**Proven first, better later.** Start with proven, lightweight methods and improve them over time, rather than inventing new ones on day one.

**Smoothness wins.** When low delay and smooth playback conflict, smoothness comes first, with buffering still kept as small as possible.

### 9.3 Settings

- One clean, unified settings model for Mic, Camera and Speaker, not controls scattered through the interface.
- Phone and PC share most settings (roughly 90%). Changing a shared setting on one side updates the other.
- **Settings belong to a pairing, not a device.** Phone A with PC A can have different values from Phone A with PC B. Reconnecting restores that pairing's settings.
- Capability is available without overwhelming the user.

---

## 10. Stability

Fault tolerance is a core requirement, not an extra. Owlmic must handle these without the user:

- A dropped connection
- The mic stopping unexpectedly
- Choppy audio or delayed video
- Unstable Wi-Fi, packet loss and stalls
- Temporary signal weakness
- Failed handshakes
- Media stopping while the connection still appears alive

**No diagnostic burden.** The user never sees words like ping, packet or transport. If a message is needed, it is short, calm and says what to try.

---

## 11. Privacy and Security

- **No accounts, no cloud, no telemetry.** Everything stays on the user's own cable or network.
- **No logs.** Nothing about calls or activity is written to disk. Monitoring data lives in memory only. There is no debug mode for now.
- **Only state is saved:** approved pairings and their settings. Nothing else.
- **No update checks.** The app never contacts the internet, not even to look for a new version.
- **Wireless links are encrypted;** cable links verify identity.
- **Everything starts off.** Mic, camera and speaker turn on only when the user chooses.
- **Approve gate.** A new phone must be approved on the PC once.
- **One owner at a time.** Only the approved, connected pair exchanges media.

---

## 12. Look and Feel

One visual language across the Android app, the Windows panel and the website.

- **Dark theme throughout.** There is no light variant.
- **Bento grid layout.** Content sits in tiles that fit tightly together.
- **Minimal gaps.** Tiles are separated by hairline gaps of about one pixel.
- **Clean and minimal.** State is obvious at a glance: on, off, connected, not connected.
- **Product, not dashboard.** It should feel like a finished product, not a developer tool.
- **Mascot used sparingly.** Owlmic's mascot appears on the landing page, the Android app icon and the tray icon only. It does not appear inside the app interface.

---

## 13. Landing Page

**Purpose:** discovery → understanding → download.

- **Product first.** The first impression is a polished product, not "an open-source project".
- **Downloads:** Android APK, Windows installer and other releases.
- **Links:** documentation, blog and resources.
- **Open source lower down:** GitHub, contributor credits and a donation option sit towards the footer.
- **Scroll-driven story.** As the visitor scrolls, animations show Owlmic working, like a running demonstration. Animation explains the product; it is never decoration.
- **Easy to find.** The site is the main discovery point, so it must be built to rank well in search.

The landing page is planned but comes after the product itself.

---

## 14. Documentation

- Covers both sides: **user documentation** (install, connect, use, fix) and **technical documentation** (how the modules work, for contributors).
- Describes this architecture and replaces the old documents.
- **No mention of "phases"** anywhere.
- Short, clear and free of clutter, in the same spirit as the product.
- This file stays as the idea and requirements. Technical detail belongs in the documentation, not here.

---

## 15. Foundations (Decided)

These choices are fixed. The detailed technical design builds on them.

| Area | Decision |
| :--- | :--- |
| **Approach** | Complete rewrite |
| **PC app** | Rust, including a native interface. No web-based interface and no runtime to install. |
| **Android app** | Native Kotlin, Android 8.0 and newer |
| **Windows** | Windows 10 and 11 |
| **Shared protocol** | Implemented separately on each side, in that platform's own language |
| **Bundled, never separate** | Everything Owlmic needs ships inside the one PC installer. Small third-party parts are allowed only if bundled. |
| **Virtual mic** | An existing signed driver for now, shown as **Owlmic Mic** if renaming proves possible. Owlmic's own driver is the long-term aim. |
| **Virtual camera** | **Owlmic Cam.** Windows 11 uses its built-in support, so no camera driver is installed there. Windows 10 gets the camera component packed in the installer. |
| **Speaker** | No driver |
| **PC installer size** | About 4 MB or less |
| **Idle cost** | Almost no RAM or CPU while only the beacon is running. Some use is acceptable while mic, camera or speaker are active. |

---

## 16. Open Source and Good Practices

- **Open and welcoming.** Public code, credited contributors, a clear route to contribute and to donate.
- **MIT licence, one repository** holding all four project areas.
- **Distribution:** the Android app on the Play Store, with the APK and the Windows installer also available from the landing page.
- **Small modules with one job each,** so they can be understood, tested and replaced on their own.
- **Measure before adding.** Any new feature must justify its cost in delay, RAM and CPU.
- **Consistency.** One design language, one settings model, one way of naming things across phone, PC and website.
- **Plain language** in the interface, the messages and the documentation.
- **Documentation moves with the product.** A change is not finished until the documents match it.

---

## 17. Open Questions

1. **PC remote controls:** should the PC be able to turn the phone's mic, camera or speaker on and off? Convenient, but it may complicate the interface.
2. **Driver naming:** can the signed mic driver be shown as "Owlmic Mic" without breaking its signature?
3. **Installer size:** can the mic driver, the Windows 10 camera component and the USB debugging tool all fit within about 4 MB? If not, which gives way?
4. **Cable and Bluetooth discovery:** these do not use a network beacon. How should phone and PC notice each other?
5. **Speaker and PC speakers:** when the phone is playing PC sound, should the PC's own speakers go quiet?
6. **Phone-side issues:** battery savers closing the app, hotspot behaviour and similar. Which need specific handling?
7. **Hard networks:** should the user be able to enter the PC's address by hand when a network blocks discovery?
8. **Settings list:** the exact settings for Mic, Camera and Speaker, and which are shared.
