# Design Language & UI System

This document outlines the visual design system, typography tokens, layout specifications, and rendering implementations for Owlmic across Android (Jetpack Compose) and Windows (Native Win32 GDI/GDI+).

The definitive design tokens and strings are defined in [`design/tokens.json`](../../design/tokens.json) and [`design/copy.json`](../../design/copy.json).

---

## 1. Design Philosophy

Owlmic adheres to a strict **pure functional** design aesthetic:
- **Zero AI Slop / Visual Bloat**: No decorative gradients, no drop shadows, no glassmorphism / translucent blur, and no springy physics animations.
- **OLED Pure Black**: Absolute black (`#000000`) canvas on mobile to conserve battery and eliminate backlight bleed.
- **Hairline Precision**: 1-pixel crisp hairlines (`#262626`) define tiles and containers.
- **High-Contrast Signal States**: Color is used exclusively for actionable state:
  - Inactive / Disabled: `#3A3A3C`
  - Active / ON: High-contrast white (`#FFFFFF`) with inverted black glyphs.
  - Live Connection / Healthy: `#30D158` (Green)
  - Waiting / Warning: `#FFD60A` (Yellow)
  - Disconnected / Error: `#FF453A` (Red)

---

## 2. Design Tokens (`design/tokens.json`)

### Color Palette
| Token | Hex Value | Purpose |
| :--- | :---: | :--- |
| `color.bg` | `#000000` | Fullscreen OLED black background |
| `color.tile` | `#0A0A0A` | Tile and card surface background |
| `color.hairline` | `#262626` | 1-pixel crisp border outlines |
| `color.text` | `#FFFFFF` | Primary high-contrast text |
| `color.text2` | `#8E8E93` | Secondary text, captions, and metrics |
| `color.disabled` | `#3A3A3C` | Disabled state or inactive indicator |
| `color.green` | `#30D158` | Connected, OK, healthy link |
| `color.yellow` | `#FFD60A` | Waiting for approval, reconnecting |
| `color.red` | `#FF453A` | Disconnected, error, blocked |
| `color.gap` | `#7E7E7E` | The 1 dp seam between phone tiles |
| `color.ink` | `#E6E6E6` | A top tile whose drawer is open (with a black icon) |
| `color.pressed` | `#1A1A1A` | A tile while pressed |
| `color.rule` | `#1C1C1E` | Row dividers in the phone drawers |
| `color.chip`, `color.chipOn` | `#141416`, `#151518` | Badges and strips in the preview area |
| `color.chipOnEdge`, `color.chipOffEdge` | `#3E3E44`, `#1A1A1D` | Edges of link buttons that are on and off |
| `color.faint` | `#555558` | The drawer footer and other faint labels |

### Typography Scale
| Style | Phone (sp / line) | PC (px / line) | Weight | Use Case |
| :--- | :---: | :---: | :---: | :--- |
| **Display** | 28 / 34 | 24 / 30 | 600 (SemiBold) | Feature headers, prominent codes |
| **Title** | 17 / 22 | 14 / 18 | 600 (SemiBold) | Tile titles, modal headings |
| **Body** | 15 / 20 | 13 / 17 | 400 (Regular) | Primary labels, button text |
| **Caption** | 12 / 16 | 11 / 14 | 400 (Regular) | Status copy, codecs, framerates |

### Spatial System & Geometry
- **Base Grid Unit**: 4 px / 4 dp.
- **Hairlines**: Exactly 1 px at all display DPI scalings.
- **Corner Radii**:
  - Android main screen tiles: 12 dp (`phone.tileRadiusDp`), on a 1 dp seam (`phone.gapDp`).
  - Windows Flyout Panel: 12 px (`radius.panelPx`).

---

## 3. Windows Native Flyout Panel (`pc/crates/owlmic-ui`)

Rather than shipping Chromium/Electron or bloated webview engines, Owlmic PC uses a **100% native Win32 GDI/GDI+ renderer**:
- **Geometry**: Fixed 560 × 255 px panel positioned above the taskbar system tray.
- **Double-Buffered Blitting**: Drawn entirely off-screen to a memory device context (DC) and blitted to screen in a single `BitBlt` call to guarantee zero flicker.
- **Instant Response**: Panel opens in < 15 ms with zero cold-start delay.
- **Live Video Preview**: A 352 × 198 px camera tile displays a 10 FPS NV12-to-RGB rendered video preview directly inside the flyout.

---

## 4. Android Compose UI (`android/app`)

The main screen follows the prototype in `landing/proto-templates`: black tiles on a 1 dp grey seam, in three bands.

| Band | Contents |
| :--- | :--- |
| Top row (72 dp) | The OWLMIC wordmark, a **?** tile that opens Setup & Help, and the link tile that opens Connection |
| Preview area | The owl on a dot grid while the camera is off; the camera preview with its quality and fps caption and the lens flip button while it is on; the mic's sound wave; the status strip while not connected |
| Bottom row (118 dp) | Camera, mic and speaker icon tiles: tap to turn on or off, long-press for that feature's settings |

- **Link tile:** its icon is the link in use (Wi-Fi, USB debugging, USB tethering, Bluetooth) and its colour is the connection state: green connected, yellow looking, approving or reconnecting, red for errors. The message itself, such as the approval code, shows in the status strip.
- **Drawers** open inside the preview area with a back arrow. Connection lists the active PC, PCs that answered (tap to choose), paired PCs and addresses added by hand, with buttons for the allowed links. Setup & Help has the three setup steps, the privacy policy and the version.
- **Feature tiles** show a short note under the icon when paused, restarting, failed or missing a permission.
- **Sideways:** the activity stays locked to portrait and the layout never moves. One quarter-turn value (0, 90 or 270, from an `OrientationEventListener` with 20 degrees of hysteresis past the diagonal; upside down and flat keep the last value) turns every glyph upright over 250 ms (`turnMs`): tile icons, the owl, the sound wave, strips, caption and flip button. The wordmark letters turn one by one and always read O to C top to bottom. Drawers and the settings sheet are laid out for the turned shape, then turned, so rows span the reader's width. The status bar hides while sideways, with black bands at both ends (`sideBandDp`).
- Single activity (`MainActivity.kt`) observing immutable state from `AppHub`; screen decisions live in `ScreenModel.kt` and are unit tested. Icons are drawn in `Glyphs.kt`; the wordmark and owl are vector drawables.
