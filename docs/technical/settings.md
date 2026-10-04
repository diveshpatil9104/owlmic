# Settings & State Synchronization

This document covers Owlmic's configuration management, versioned key-value store, persistence model, and bidirectional synchronization across devices.

---

## 1. Single Source of Truth (`design/settings.json`)

All setting keys, allowable value enums, scopes, and target UI visibility rules are declared centrally in `design/settings.json`. Both the Android client and the Windows application compile their setting models directly from this schema.

### Schema Fields
- `id`: Dotted string identifier (e.g., `camera.quality`, `mic.noiseReduction`).
- `values`: String enum of allowable values.
- `default`: The fallback default setting value.
- `scope`:
  - `shared`: Synchronized between phone and PC. Changing the value on either device updates the other.
  - `pc`: Local to the Windows PC.
- `shownOn`: Array indicating where the setting is exposed in the UI (`["phone", "pc"]` or single platform).

### Settings Inventory

| Setting ID | Values | Default | Scope | Shown On | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `mic.noiseReduction` | `phone`, `phoneAndPc`, `off` | `phone` | shared | phone, pc | Microphone DSP noise filtering mode |
| `mic.boost` | `off`, `plus6`, `plus12` | `off` | shared | phone, pc | Hardware pre-gain boost |
| `camera.lens` | `back`, `front` | `back` | shared | phone, pc | Active camera lens |
| `camera.quality` | `auto`, `720p`, `1080p` | `auto` | shared | phone, pc | Video streaming resolution |
| `camera.frameRate` | `24`, `30`, `60` | `24` | shared | phone, pc | Video capture frame rate |
| `camera.orientation` | `auto`, `landscape`, `portrait` | `auto` | shared | phone | Camera sensor orientation lock |
| `camera.framing` | `fill`, `fit` | `fill` | pc | pc | Cropping mode on PC virtual camera |
| `camera.mirror` | `off`, `on` | `off` | pc | pc | Horizontal video mirroring |
| `speaker.quietPc` | `on`, `off` | `on` | pc | pc | Mute PC physical speakers during loopback |
| `link.usbDebugging` | `on`, `off` | `on` | shared | phone, pc | Allow Level 1 ADB transport |
| `link.usbTethering` | `on`, `off` | `on` | shared | phone, pc | Allow Level 2 USB tethering transport |
| `link.wifi` | `on`, `off` | `on` | shared | phone, pc | Allow Level 3 Wi-Fi transport |
| `link.bluetooth` | `on`, `off` | `on` | shared | phone, pc | Allow Level 4 Bluetooth transport |
| `general.startWithWindows` | `on`, `off` | `on` | pc | pc | Windows Run registry autostart |

---

## 2. Synchronization Protocol

Shared settings synchronize via `SETTINGS` (`0x21`) control messages:

```json
{
  "changes": [
    {
      "id": "camera.quality",
      "value": "1080p",
      "version": 4
    }
  ]
}
```

### Versioning & Conflict Resolution
- Every change to a setting increments its local 64-bit integer version counter.
- When a `SETTINGS` frame is received, the receiver compares incoming versions against its current state.
- **Higher Version Wins**: The newer version replaces the existing value.
- **Tie Breaker**: If versions are identical, the PC's setting value takes precedence to ensure determinism.

---

## 3. Storage & Persistence

### Windows PC
- Stored as human-readable JSON in `%APPDATA%\Owlmic\owlmic.json`.
- Holds:
  - Local PC preferences.
  - Per-phone paired device records, including phone name, static public key, last-used link, and paired setting book.
- Flushed to disk atomically using write-to-temp and rename to prevent corruption on unexpected power loss.

### Android Phone
- Stored in private application storage using Android `SharedPreferences` or EncryptedSharedPreferences.
- Cleared automatically if the user uninstalls the app or clears app data.
