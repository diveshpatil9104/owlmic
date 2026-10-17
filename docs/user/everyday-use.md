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
+------------------------+-----+-----+
| OWLMIC                 |  ?  |  @  |   ? = Setup & Help, @ = link tile
+------------------------+-----+-----+
|                                    |
|            preview area            |
|   (owl, camera, sound wave and     |
|    status messages show here)      |
|                                    |
+------------+-----------+-----------+
|   camera   |    mic    |  speaker  |
+------------+-----------+-----------+
```

- **Camera, mic and speaker tiles**: tap to turn each one on or off. Long-press a tile for its settings.
  - A short note under the icon says when it is paused, restarting, failed ("Didn't start. Tap to try again.") or needs a permission.
- **Preview area**: shows the owl while everything is off.
  - With the camera on: the live picture, the quality and frame rate (for example `1080p · 30 fps`) and a button to flip between the back and front lenses.
  - With the mic on: a sound wave that follows your voice.
  - While not connected: a short message, such as "Looking for your PC", or the 4-digit code to approve on your PC.
- **Link tile** (top right): its icon shows the link in use (Wi-Fi, USB debugging, USB tethering or Bluetooth) and its colour the state: green connected, yellow looking or waiting, red when something needs you. Tap it to see your PCs and choose one, and to pick which links Owlmic may use.
- **? tile**: the three setup steps, the privacy policy and the app version.
- **Turning the phone sideways**: the tiles stay where they are and everything on them turns upright, including the drawers and settings, so the phone can stand sideways during a call.

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
