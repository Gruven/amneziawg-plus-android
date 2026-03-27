# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Android client for AmneziaWG — a WireGuard fork with additional obfuscation capabilities. Multi-module Gradle project with native code in Go and C.

## Build commands

```bash
# Clone (with submodules — required!)
git clone --recurse-submodules https://github.com/Gruven/amneziawg-android

# Debug APK
./gradlew assembleDebug

# Release AAB (requires keystore)
./gradlew bundleRelease

# Release APK
./gradlew assembleRelease

# Unit tests
./gradlew test

# Tests for a specific module
./gradlew :tunnel:test
```

**macOS**: native code build requires `flock(1)` — install via `brew install discoteq/flock/flock`.

## Modules

- **`ui/`** — Android application (Kotlin). Activities, Fragments, ViewModels, resources. Package: `org.amnezia.awg`
- **`tunnel/`** — Tunnel library (Java). Configs, cryptography, VPN backends, JNI bindings to native code

## Architecture

**MVVM** with Android Data Binding. UI layer in Kotlin, tunnel logic in Java.

### UI module (`ui/src/main/java/org/amnezia/awg/`)
- `activity/` — Activities. `BaseActivity` — shared base class. `MainActivity` for phones, `TvMainActivity` for Android TV (Leanback)
- `fragment/` — Fragments. Tunnel list, details, config editor
- `viewmodel/` — Proxy classes (`InterfaceProxy`, `PeerProxy`, `ConfigProxy`) for data binding
- `Application.kt` — App entry point, backend initialization
- `QuickTileService.kt` — Quick Settings tile
- `BootShutdownReceiver.kt` — Auto-start on boot
- `TaskerFireReceiver.kt` — Tasker plugin action receiver
- `activity/TaskerEditActivity.kt` — Tasker plugin configuration UI

### Tunnel module (`tunnel/src/main/java/org/amnezia/awg/`)
- `backend/` — `Backend` interface, `GoBackend` (primary, via JNI), `RootGoBackend` (root-based, no VPN API), `AwgQuickBackend` (alternative via root)
- `config/` — WireGuard/AmneziaWG config parsing (`Config`, `Interface`, `Peer`, `InetEndpoint`)
- `crypto/` — Curve25519, `Key`, `KeyPair`
- `util/` — `RootShell`, `ToolsInstaller`, `SharedLibraryLoader`

### Native code (`tunnel/tools/`)
- `libwg-go/` — Go WireGuard implementation (primary backend). JNI via `api-android.go` + `jni.c`. Go 1.24.2 is downloaded automatically during build
- `tun-creator.c` — Helper binary executed as root to create TUN interface and pass fd via Unix socket (SCM_RIGHTS)
- `amneziawg-tools/` — C CLI tools implementation (git submodule)
- `elf-cleaner/` — Utility for .so compatibility with API < 21 (git submodule)
- `CMakeLists.txt` — NDK build configuration, produces `libwg-go.so`, `libwg.so`, `libwg-quick.so`, `libawg-tun-creator.so`

## Key build parameters

- `compileSdk`: 35, `minSdk`: 21, `targetSdk`: 35
- NDK: 26.1.10909125
- Java: 17
- Kotlin: 2.2.0
- ProGuard enabled in release (`ui/proguard-android-optimize.txt`)
- Version is set in `gradle.properties` (`versionName`, `versionCode`)

## Testing

Unit tests are in `tunnel/src/test/`. Config parsing tests (`ConfigTest.java`) and error handling tests (`BadConfigExceptionTest.java`). Test configs are in `tunnel/src/test/resources/`.

## CI/CD

GitHub Actions (`.github/workflows/build.yml`) — manual dispatch. Release secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`.

## Git Submodules

The project uses submodules (`amneziawg-tools`, `elf-cleaner`). After cloning or switching branches: `git submodule update --init --recursive`.
