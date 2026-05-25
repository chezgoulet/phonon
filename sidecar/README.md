# sidecar — Android inference sidecar

The phone-side agent for the Phonon inference cluster. Runs as a foreground service on each Android phone.

Part of the [phonon](https://github.com/chezgoulet/phonon) monorepo.

## Build

```bash
cd sidecar && ./gradlew assembleRelease
```

Requires Android SDK 35+ with Kotlin 2.1 and AGP 8.7.3.

APK is signed with your own key. Install via ADB:

```bash
adb install sidecar/app/build/outputs/apk/release/app-release.apk
```

## Protocol

| Endpoint | Direction | Purpose |
|---|---|---|
| `POST /api/v1/sidecar/register` | Phone → Coordinator | Initial registration |
| `POST /api/v1/sidecar/heartbeat` | Phone → Coordinator | Health telemetry (60s) |
| `POST /api/v1/sidecar/pair` | Phone → Coordinator | Pair with security token |
| `/ws?device_id=...` | Phone ↔ Coordinator | WebSocket command channel |
| `POST /infer` | Coordinator → Phone | Local inference (proxied to OlliteRT) |

## Dependencies

- **OkHttp 4.12** — HTTP client + WebSocket
- **Zero Play Services** — Android NSD for mDNS, `com.sun.net.httpserver` for local HTTP
