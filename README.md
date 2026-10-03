# Owlmic

<p align="center">
  <a href="https://github.com/diveshpatil9104/owlmic/releases"><img src="https://img.shields.io/github/v/release/diveshpatil9104/owlmic?include_prereleases&style=for-the-badge&color=FFFFFF&label=Download" alt="Download"></a>
  <a href="https://github.com/diveshpatil9104/owlmic/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/diveshpatil9104/owlmic/ci.yml?branch=main&style=for-the-badge&label=CI" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License: MIT"></a>
  <a href="https://github.com/diveshpatil9104/owlmic/discussions"><img src="https://img.shields.io/badge/Discussions-Join-blue?style=for-the-badge&logo=github" alt="Discussions"></a>
  <a href="https://github.com/diveshpatil9104/owlmic/stargazers"><img src="https://img.shields.io/github/stars/diveshpatil9104/owlmic?style=for-the-badge&color=FFD60A&logo=github" alt="Stars"></a>
</p>

<p align="center"><strong>Fast. Quick. Lightweight. Seamless.</strong></p>

Owlmic turns your Android phone into your Windows PC's **microphone, camera and speaker**. Zoom, Meet, Teams, Discord and OBS see **Owlmic Mic** and **Owlmic Cam** as normal hardware.

> **Owlmic is being rebuilt from the ground up.** The current download is the v0.1.0 preview. The rebuilt app arrives as v1.0.0.

## What it does

| Feature | What happens |
| :--- | :--- |
| **Mic** | The phone's microphone becomes the PC's microphone, Owlmic Mic. |
| **Camera** | The phone's camera becomes the PC's webcam, Owlmic Cam. |
| **Speaker** | Whatever the PC plays comes out of the phone. |

## Why Owlmic

- **Fast:** connected about a second after you open the app.
- **Light:** a PC installer of about 4 MB, and almost no CPU or memory while idle.
- **Self-healing:** if a link drops or a stream stalls, Owlmic recovers by itself.
- **Convenient:** one installer on the PC, one app on the phone, no extra downloads.
- **Private:** no account, no cloud, no tracking, no logs. Everything stays on your cable or your own network.

## How it works

**First time:** install Owlmic on the PC and the phone, open the phone app, and approve the phone once on the PC.
**Every time after that:** open the app, it connects, tap Mic, Camera or Speaker.

Owlmic picks the best link by itself and moves between them without a gap:

| Link | Notes |
| :--- | :--- |
| USB debugging | Best quality, lowest delay |
| USB tethering | Nearly as good, same cable |
| Wi-Fi | Same network, or the phone's hotspot |
| Bluetooth | Audio only, works almost anywhere |

## Requirements

- **Phone:** Android 8.0 or newer
- **PC:** Windows 10 (1809 or newer) or Windows 11, 64-bit

## Build from source

**Windows app** (on Windows, with [Rust](https://rustup.rs/) and the Visual C++ Build Tools):

```powershell
cd pc
cargo build --release
```

**Android app** (with Android Studio or the Android SDK, CMake and the NDK):

```bash
git submodule update --init
cd android
./gradlew assembleDebug
```

## Project layout

| Folder | What |
| :--- | :--- |
| `android/` | The phone app (Kotlin) |
| `pc/` | The Windows app and its installer (Rust, Inno Setup) |
| `.github/` | CI, issue templates and contributor guides |

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](.github/CONTRIBUTING.md) to get started, and say hello in [Discussions](https://github.com/diveshpatil9104/owlmic/discussions).

## License

[MIT](LICENSE). Owlmic bundles VB-CABLE (VB-Audio), softcam (MIT), RNNoise (BSD) and Opus (BSD); see `pc/installer/THIRD-PARTY-NOTICES.txt`.
