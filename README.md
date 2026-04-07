# AmneziaWG+

Android GUI for [AmneziaWG](https://docs.amnezia.org/documentation/amnezia-wg/)

> [!NOTE]
> This is a fork of the original [amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) with additional features listed below. For the upstream version, please visit the [original repository](https://github.com/amnezia-vpn/amneziawg-android).

## ✨ Additional features

- **Android 4.4 support** — Minimum and target SDK lowered to 19 (Android 4.4 KitKat). This legacy branch is only for Android 4.4 users. For Android 5.0+ please use the [master branch](https://github.com/Gruven/amneziawg-plus-android).
- **Root mode** — Optional tunnel backend that uses root access to create TUN interfaces and configure routing via `iptables`/`ip route`, completely bypassing the Android VPN API. No VPN icon in the status bar, no VPN permission dialogs. All device traffic is routed through the tunnel. Can be enabled in settings. **Requires a rooted device** (SuperSU, Magisk tested).
- **Tasker plugin** — Integrates as a Tasker action plugin for automation. Select a tunnel and action (on/off/toggle) directly from Tasker.
- **Token-based intent authentication** — Replaced the `CONTROL_TUNNELS` Android permission with a simple token for intent API authentication. No need to declare permissions in the calling app's manifest — just pass the token as an intent extra. More compatible with `adb`, scripts, and automation tools.
- **Per-ABI APKs** — Separate APKs for each CPU architecture (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`). Smaller download size compared to a universal APK.
- **UI rewrite** — Simplified UI for Android 4.4.

## 🤖 Automation

### 🔌 Tasker plugin

Requires **"Allow Tasker plugin"** to be enabled in settings.

The app registers as a [Tasker](https://tasker.joaoapps.com/) action plugin:

1. In Tasker: **Task → Add Action → Plugin → AmneziaWG Tunnel Control**
2. Tap the pencil icon to configure
3. Select a tunnel and action (**Turn on** / **Turn off** / **Toggle**)
4. Tap **Save**

The plugin appears automatically in Tasker's plugin list once the app is installed. It also works with other automation apps that support the Locale plugin protocol (e.g. [MacroDroid](https://www.macrodroid.com/)).

### 📡 Intent API

Requires **"Allow remote intents"** to be enabled in settings.

External apps can control tunnels via broadcast intents. Authentication is done via a token that is generated when you enable "Allow remote intents". You can view, copy, or regenerate the token in the **"Remote control token"** settings entry.

| Action | Extras | Description |
|--------|--------|-------------|
| `org.amnezia.awg.action.SET_TUNNEL_UP` | `tunnel` (String), `token` (String) | Bring tunnel up |
| `org.amnezia.awg.action.SET_TUNNEL_DOWN` | `tunnel` (String), `token` (String) | Bring tunnel down |
| `org.amnezia.awg.action.REFRESH_TUNNEL_STATES` | — | Refresh all tunnel states (always available, no token needed) |

Example using `adb`:

```bash
adb shell am broadcast -a org.amnezia.awg.action.SET_TUNNEL_UP \
  -e tunnel "my-tunnel" \
  -e token "AbCdEf1234" \
  -n org.amnezia.awg/.model.TunnelManager\$IntentReceiver
```

## 🔨 Building

```
$ git clone --recurse-submodules https://github.com/Gruven/amneziawg-android
$ cd amneziawg-android
$ ./gradlew assembleRelease
```

macOS users may need [flock(1)](https://github.com/discoteq/flock).

## 🐛 Issues

Issues are welcome as long as they relate to the [additional features](#-additional-features) of this fork (AmneziaWG+). If you encounter a bug of the original application (AmneziaWG), please open an issue in the [upstream repository](https://github.com/amnezia-vpn/amneziawg-android/issues).

## 🤝 Contributing

PRs are welcome — bug fixes, documentation improvements, refactoring. The exception is new features: please open a [feature request](https://github.com/Gruven/amneziawg-android/issues) first to discuss the idea before starting work.

## 👀 But why?..

This project primarily aims to support devices that, for whatever reason, are still running older versions of Android. In my case, it's the head unit in my 2021 car, which runs Android 5.1 with the VPN API stripped out.

But! This project also includes quality-of-life features that make it worth using on modern devices too (which I do on my phone).

## ⭐️ Alternatives

If the extra features of this project don't interest you, consider these alternatives:
- [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-android) (Android 7+) — the original app.
- [WG Tunnel](https://github.com/wgtunnel/android) (Android 8+) — an excellent alternative WG/AWG client with many additional features and a polished UI.
