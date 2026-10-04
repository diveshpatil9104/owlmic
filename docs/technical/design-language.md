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
  - Android Tiles: 16 dp (`radius.cardPx`).
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

- Built with modern Jetpack Compose.
- Single activity architecture (`MainActivity.kt`) observing immutable state from `AppHub`.
- Full edge-to-edge support with custom drawn vector icons (`Glyphs.kt`).
- Built-in live audio meter rendered dynamically using Canvas drawing with zero recomposition thrashing.
