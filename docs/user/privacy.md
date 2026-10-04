# Privacy & Security Model

Owlmic is built with an uncompromising commitment to privacy and data sovereignty. Your voice, your video, and your keystrokes belong exclusively to you.

---

## 1. Zero Cloud & Zero Telemetry

- **No Servers**: Owlmic operates exclusively over peer-to-peer local connections between your phone and your PC. There are no intermediary cloud relays, STUN/TURN servers, or third-party gateways.
- **Zero Analytics**: Owlmic contains no analytics, telemetry SDKs, crash trackers, ad frameworks, or identifier beacons. No data leaves your local network.
- **No Account Required**: You do not register, create a username, or link an email.

---

## 2. Default-OFF Hardware Policy

- **Strict Manual Control**: When Owlmic launches or reconnects, your microphone and camera always initialize in the **OFF** state.
- **No Background Activation**: The PC cannot remotely force your phone's camera or microphone to turn on without your phone showing the active, high-visibility UI and persistent Android notification.
- **Visual Feedback**: When the camera is active, Android displays the green camera privacy indicator in the status bar, and Owlmic's on-screen tile illuminates.

---

## 3. Cryptographic Trust & Encryption

- **Trust-On-First-Use (TOFU)**: Every device generates an immutable NIST P-256 cryptographic identity key pair on first launch.
- **Two-Factor Code Verification**: When an unknown phone connects, Owlmic requires physical confirmation of a 4-digit numeric code on both screens before any data can flow.
- **AES-256-GCM Encryption**: All wireless communications (Wi-Fi LAN and Bluetooth) are encrypted using AES-256-GCM with independent per-direction keys and 64-packet replay window protection.
- **Wired Bus Safety**: USB debugging and tethering traffic travels directly over the hardware USB bus and is never exposed to the wider local network.

---

## 4. Local Storage & Keys

- **Windows PC**: Stores paired device tokens and user preferences in `%APPDATA%\Owlmic\owlmic.json`. Private keys are generated locally and never exported.
- **Android Phone**: Stores paired PC tokens in private application storage. Clearing app data removes all pairing associations.
