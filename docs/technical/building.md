# Building Owlmic

This document provides step-by-step instructions for building the Android client and the Windows PC application from source.

---

## 1. Prerequisites

### Windows PC App
- **Rust**: Version 1.80+ (stable toolchain: `x86_64-pc-windows-msvc` or `x86_64-pc-windows-gnu`).
- **Inno Setup**: Version 6.2+ (for building the installer executable `ISCC.exe`).
- **Git**: With submodule support.

### Android Client
- **JDK**: Java Development Kit 17 (Temurin or OpenJDK recommended).
- **Android SDK**: API 34+ with Android NDK version 26+ installed.
- **CMake**: Version 3.22+ (for compiling native Oboe and Opus JNI libraries).

---

## 2. Building the PC App (Rust)

1. **Clone the Repository with Submodules**:
   ```bash
   git clone --recurse-submodules https://github.com/diveshpatil9104/owlmic.git
   cd owlmic/pc
   ```

2. **Run Tests**:
   ```bash
   cargo test --workspace
   ```

3. **Check Formatting & Linting**:
   ```bash
   cargo fmt --all --check
   cargo clippy --all-targets -- -D warnings
   ```

4. **Build Release Binaries**:
   ```bash
   cargo build --release
   ```
   This generates:
   - `target/release/owlmic.exe`: The primary background tray application.
   - `target/release/owlmic_vcam.dll`: Windows 11 Media Foundation virtual camera driver.

5. **Compile the Installer**:
   To compile the full self-contained installer (`Owlmic-Setup-1.0.0.exe`):
   ```powershell
   cd installer
   .\build-installer.ps1
   ```

---

## 3. Building the Android App (Kotlin & NDK)

1. **Navigate to the Android Directory**:
   ```bash
   cd owlmic/android
   ```

2. **Run Unit Tests & Linting**:
   ```bash
   ./gradlew testDebugUnitTest lintDebug
   ```

3. **Build Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

4. **Build Release APK & App Bundle**:
   ```bash
   ./gradlew assembleRelease bundleRelease
   ```

---

## 4. Building the Web Landing Page (`landing/`)

1. **Navigate to Landing Directory**:
   ```bash
   cd owlmic/landing
   ```

2. **Install Dependencies & Build**:
   ```bash
   npm install
   npm run build
   ```
   The static site assets are output to `landing/dist/`.
