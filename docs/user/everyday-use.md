# Everyday Use

Once paired, Owlmic operates transparently. This guide covers daily controls, switching cameras, muting/unmuting, configuring in meeting apps, and using the speaker loopback.

---

## 1. Daily Connection Workflow

1. Unlock your phone and open **Owlmic** (or tap the persistent notification if background service is running).
2. Within seconds, the app connects to your PC and displays the main dashboard.
3. The mic, camera, and speaker start in the **OFF** state by default for privacy.

---

## 2. Phone Controls & Dashboard

```
┌───────────────────────────────────────┐
│ Owlmic                            ⚙  │
│ 🟢 Connected via USB                 │
├───────────────────────────────────────┤
│                                       │
│    ┌─────────────┐  ┌─────────────┐   │
│    │    🎙️ Mic   │  │  📷 Camera  │   │
│    │     OFF     │  │     OFF     │   │
│    └─────────────┘  └─────────────┘   │
│                                       │
│    ┌──────────────────────────────┐   │
│    │          🔊 Speaker          │   │
│    │             OFF              │   │
│    └──────────────────────────────┘   │
│                                       │
└───────────────────────────────────────┘
```

- **Mic Tile**: Tap to turn the microphone ON or OFF.
  - When ON, the tile highlights in high-contrast white.
  - The live audio level meter at the bottom of the tile bounces to reflect your voice loudness.
- **Camera Tile**: Tap to turn the camera ON or OFF.
  - When ON, a small flip camera button appears to switch between **Back** and **Front** lenses.
  - Displays resolution and framerate badge (e.g., `1080p · 30 fps`).
- **Speaker Tile**: Tap to route your PC's audio output through your phone speaker or connected headphones.

---

## 3. PC Tray Flyout & Controls

Click the **Owl icon** in your Windows taskbar system tray:
- **Phone Status Tile**: Shows the connected phone model (e.g., "Pixel 8"), active transport (e.g., "USB debugging" or "Wi-Fi"), and battery/signal health.
- **Mic Tile**: Displays whether Owlmic Mic is currently in use by any Windows application (Discord, Teams, Zoom). Allows one-click muting from the PC.
- **Camera Tile & Live Preview**: Displays a smooth 10 FPS low-latency thumbnail of what Owlmic Cam is currently capturing and broadcasting to Windows apps.
- **Speaker Tile**: Toggle PC audio redirection. When enabled, Owlmic captures Windows system audio and streams it to your phone.
  - *Quiet PC Speakers option*: When enabled, Owlmic automatically mutes your PC's desktop speakers while streaming audio to your phone.

---

## 4. Selecting Owlmic in Meeting & Streaming Apps

### Discord
1. Go to **User Settings** (gear icon) -> **Voice & Video**.
2. **Input Device**: Select **Owlmic Mic (Owlmic Bridge)**.
3. **Camera**: Select **Owlmic Cam**.

### Zoom / Microsoft Teams / Google Meet
1. Open Settings -> **Audio**.
2. **Microphone**: Select **Owlmic Mic**.
3. Open Settings -> **Video**.
4. **Camera**: Select **Owlmic Cam**.

### OBS Studio
- **Audio Input Capture**: Add a new source and select **Owlmic Mic**.
- **Video Capture Device**: Add a new source and select **Owlmic Cam**. Set resolution to Match Source (up to 1080p60).

---

## 5. Quick Notification Controls

When Owlmic is actively streaming, Android displays a low-overhead, persistent media notification in your notification tray:
- Shows the current active streams (e.g., `Mic & Camera on · Desktop-PC`).
- **Pause Mic**: Temporarily suspends mic audio capture.
- **Stop All**: Immediately halts all media streaming and sets hardware sensors to sleep.
