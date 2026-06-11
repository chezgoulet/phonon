# phonon

**Used phones → unified AI inference.** One endpoint, zero marginal cost.

Phonon turns a handful of cracked-screen Pixels into a private, local inference backend. Install the APK, plug in power and ethernet, point your agent framework at the endpoint. The coordinator handles discovery, health monitoring, load balancing, and model management. You get a cluster that behaves like one API.

## Components

| Layer | Language | Location |
|---|---|---|
| **Coordinator** — API, routing, model cache, health monitoring | Go | `cmd/`, `internal/` |
| **Sidecar** — Android phone agent, foreground service, mDNS, inference | Kotlin | `sidecar/` |

## Quick Start

1. **Build the coordinator.** `go build ./cmd/phonon-coordinator`
2. **Build the APK.** `cd sidecar && ./gradlew assembleRelease`
3. **Install the APK** on each phone via `adb install sidecar/app/build/outputs/apk/release/app-release.apk`
4. **Start the coordinator** — `./phonon-coordinator`
5. **Phones appear automatically.** Pair them in the UI.

---

### Coordinator

The coordinator is a single Go binary. It handles:
- mDNS discovery of phones on the LAN
- REST + WebSocket protocol for phone management
- Model caching with HuggingFace download + SHA-256 verification
- Health monitoring with automatic exclusion for overheating/low battery
- OpenAI-compatible API (`POST /v1/chat/completions`, `GET /v1/models`)
- OIDC auth middleware with JWKS caching
- Event log with JSON-lines file backend

Build: `CGO_ENABLED=0 go build ./cmd/phonon-coordinator`

Config: `phonon.yaml` (or `$PHONON_CONFIG`)

### Sidecar

The sidecar is a Kotlin Android app that runs as a foreground service on each phone. It:
- Announces itself via mDNS on `_phonon._tcp`
- Registers with the coordinator via REST
- Maintains a WebSocket command channel
- Reports health telemetry (battery, temperature, storage) every 60s
- Downloads model files via HTTP Range requests
- Loads and runs LiteRT-LM models directly (NPU-accelerated on Pixel Tensor chips)
- Serves inference via LiteRT-LM's Kotlin SDK (no separate engine process)

Build: `cd sidecar && ./gradlew assembleRelease` (Android SDK 35+, Kotlin 2.1)

Zero Google Play Services — works on GrapheneOS.

## Architecture

```
┌─────────────┐     ┌──────────────────┐     ┌──────────────┐
│             │     │   Coordinator    │     │              │
│  LiteLLM /  │────▶│  (Go binary)     │────▶│  Phone pool  │
│  Agent      │     │  :8080           │     │  (6 Pixels)  │
│  Framework  │     │  REST + WS + UI  │     │  5W each     │
│             │     └──────┬───────────┘     └──────────────┘
└─────────────┘            │
                           │ mDNS + WS + REST
                           │
                    ┌──────┴──────┐
                    │  Sidecar    │
                    │  (Kotlin    │
                    │   APK)      │
                    └─────────────┘
```
