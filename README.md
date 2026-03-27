# Android GUI for [AmneziaWG](https://docs.amnezia.org/ru/documentation/amnezia-wg/)

> [!NOTE]
> This is a fork of the original [amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) with additional features listed below. For the upstream version, please visit the [original repository](https://github.com/amnezia-vpn/amneziawg-android).

## Additional features

- **Root mode (no VPN API)** — Tunnel backend that uses root access to create TUN interfaces and configure routing via `iptables`/`ip route`, completely bypassing the Android VPN API. No VPN icon in the status bar, no VPN permission dialogs. All device traffic is routed through the tunnel.
- **Android 5.0+ support** — Minimum SDK lowered to 21 (Android 5.0 Lollipop).
- **Tasker plugin** — Integrates as a Tasker action plugin for automation. Select a tunnel and action (on/off/toggle) directly from Tasker.

## Automation

### Tasker plugin

Requires **"Allow Tasker plugin"** to be enabled in settings.

The app registers as a [Tasker](https://tasker.joaoapps.com/) action plugin:

1. In Tasker: **Task → Add Action → Plugin → AmneziaWG Tunnel Control**
2. Tap the pencil icon to configure
3. Select a tunnel and action (**Turn on** / **Turn off** / **Toggle**)
4. Tap **Save**

The plugin appears automatically in Tasker's plugin list once the app is installed. It also works with other automation apps that support the Locale plugin protocol (e.g. [MacroDroid](https://www.macrodroid.com/)).

### Intent API (original feature)

Requires **"Allow remote intents"** to be enabled in settings.

External apps can control tunnels via broadcast intents.

Required permission: `org.amnezia.awg.permission.CONTROL_TUNNELS`

| Action | Extra | Description |
|--------|-------|-------------|
| `org.amnezia.awg.action.SET_TUNNEL_UP` | `tunnel` (String) — tunnel name | Bring tunnel up |
| `org.amnezia.awg.action.SET_TUNNEL_DOWN` | `tunnel` (String) — tunnel name | Bring tunnel down |
| `org.amnezia.awg.action.REFRESH_TUNNEL_STATES` | — | Refresh all tunnel states (always available) |

The calling app must declare the permission in its `AndroidManifest.xml`:

```xml
<uses-permission android:name="org.amnezia.awg.permission.CONTROL_TUNNELS" />
```

On Android before 6.0 permission already granted during installation. On Android 6.0+ the user must grant it at runtime, or via `adb`:

```bash
adb shell pm grant <caller_package> org.amnezia.awg.permission.CONTROL_TUNNELS
```

Example using `adb`:

```bash
adb shell am broadcast -a org.amnezia.awg.action.SET_TUNNEL_UP -e tunnel "my-tunnel" -n org.amnezia.awg/.model.TunnelManager\$IntentReceiver
```

## Building

```
$ git clone --recurse-submodules https://github.com/Gruven/amneziawg-android
$ cd amneziawg-android
$ ./gradlew assembleRelease
```

macOS users may need [flock(1)](https://github.com/discoteq/flock).
