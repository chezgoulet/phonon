# Phonon — Alpha

**Used phones → unified AI inference.**

Phonon turns a handful of used Pixels (or any Android phone) into a private,
local inference cluster. Install the APK, plug in power and ethernet, point
your agent framework at the endpoint. The coordinator handles discovery,
health monitoring, load balancing, and model management.

One endpoint, zero marginal cost. No cloud, no per-token billing, no one
reading your prompts.

> **ALPHA** — This is an early release. APIs and configuration are subject
> to change. See [PHONON.md](PHONON.md) for the full design spec.

---

## Table of Contents

- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Building from Source](#building-from-source)
- [Configuration](#configuration)
- [Pairing Phones](#pairing-phones)
- [Using the API](#using-the-api)
- [Authentication](#authentication)
- [Dashboard / Web UI](#dashboard--web-ui)
- [Troubleshooting](#troubleshooting)
- [Architecture](#architecture)
- [Documentation](#documentation)

---

## Requirements

### Phones (minimum 1)

- **Android** 12+ (API 31) — tested on Pixel 6–9, Moto G Stylus 5G
- **Storage:** 16 GB minimum (for the APK + small models); 64–128 GB for
  larger models (QLoRA quantized GGUF files)
- **Power:** USB power bank or charger; wireless charging stand recommended
- **Network:** Ethernet adapter preferred (USB-C hub); Wi-Fi works for
  pool mode (single-phone inference)
- **OS:** Stock Android or GrapheneOS — no Google Play Services required

### Coordinator machine (minimum 1)

- Any Linux, macOS, or Windows (via WSL2) machine on the same LAN
- Docker (recommended) or Go 1.22+ to build from source
- Network accessible from all phones on port 8080 (configurable)

### Network

- Phones and coordinator must be on the same subnet for mDNS/discovery
- 1 GbE switch recommended for shard mode; Wi-Fi fine for pool mode
- No internet access required after initial model downloads

---

## Quick Start

### 1. Build the coordinator

**Option A — Docker (recommended):**

```bash
docker compose up -d
```

Configuration is read from `phonon.yaml` (copy from `phonon.example.yaml`).

**Option B — From source:**

```bash
go build -o phonon-coordinator ./cmd/phonon-coordinator
./phonon-coordinator
```

### 2. Install the APK on each phone

```bash
cd sidecar
./gradlew assembleRelease
adb install sidecar/app/build/outputs/apk/release/app-release.apk
```

On first launch the sidecar requests **Notification permission** (required for
the foreground service) and **Nearby Devices** permission (for mDNS). Grant
both.

### 3. Phones appear automatically

The sidecar announces itself via mDNS on `_phonon._tcp`. The coordinator
discovers it within seconds and the phone appears in the node list:

```bash
curl http://coordinator-ip:8080/api/v1/cluster/nodes
```

### 4. Pair phones (if authentication is enabled)

If `auth.mode` is set to anything other than `none`, phones must be paired.
See [Pairing Phones](#pairing-phones).

### 5. Configure groups

Edit `phonon.yaml` to define inference groups:

```yaml
groups:
  - name: "fast-reasoning"
    mode: "pool"            # "pool" or "shard" (experimental)
    model: "gemma-3-12b-it-q4_k_m.gguf"
    runtime: "litert"       # "litert" (NPU) or "prima" (CPU)
    phones: ["phone-kitchen", "phone-livingroom"]
    download_url: "https://huggingface.co/bartowski/gemma-3-12b-it-GGUF/resolve/main/gemma-3-12b-it-Q4_K_M.gguf"
    checksum: "sha256:..."
```

The coordinator's reconciler detects the config change and pushes the model
to each phone automatically.

### 6. Inference

```bash
curl http://coordinator-ip:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-3-12b-it-q4_k_m.gguf",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'
```

---

## Building from Source

### Coordinator

```bash
CGO_ENABLED=0 go build ./cmd/phonon-coordinator
```

The binary has no runtime dependencies beyond the host OS. Cross-compile for
any target with `GOOS=linux GOARCH=arm64`.

### Sidecar APK

```bash
cd sidecar
./gradlew assembleRelease
# APK at: sidecar/app/build/outputs/apk/release/app-release.apk
```

Requires Android SDK 35+ and Kotlin 2.1. The SDK can be installed via
Android Studio or `sdkmanager`.

---

## Configuration

Copy `phonon.example.yaml` to `phonon.yaml` and adjust:

| Field | Default | Description |
|---|---|---|
| `cluster.name` | `"chezgoulet"` | Cluster name (shown in web UI) |
| `cluster.auth.mode` | `"none"` | Auth mode: `"none"`, `"psk"`, or `"oidc"` |
| `cluster.tls.enabled` | `false` | Enable HTTPS |
| `cluster.networking.prefer` | `"ethernet"` | Prefer `"ethernet"` or `"wifi"` |
| `cluster.health.offline_timeout` | `"60s"` | Mark phone offline after no heartbeat |
| `queue.max_per_node` | `3` | Max concurrent requests per phone |

### Environment Variables

| Variable | Overrides |
|---|---|
| `PHONON_PORT` | Coordinator listen port (default `8080`) |
| `PHONON_COORDINATOR_URL` | Public coordinator URL for phone model downloads |
| `PHONON_CACHE_DIR` | Model cache directory (default `./cache`) |
| `PHONON_PSK` | Pre-shared key for PSK auth (override YAML) |
| `PHONON_CONFIG` | Config file path (default `phonon.yaml`) |

---

## Pairing Phones

Pairing is **enforced**, and it gates both directions of trust:

- A paired phone refuses **all inference requests** that don't carry its
  pairing token — the phone only takes inference orders from its paired
  coordinator, never from arbitrary devices on the LAN. Unpaired phones
  refuse inference entirely.
- The coordinator rejects heartbeats, model-status updates, and WebSocket
  command connections from a paired device unless they carry that device's
  token, so nobody on the LAN can impersonate or hijack a paired phone.

The token is a per-device secret created when the operator confirms the
pairing. The phone retrieves it by polling the pairing-status endpoint with
an Ed25519 signature from its pinned device key, so only the phone that
initiated the pairing can ever receive it. Unpairing a device (UI or API)
invalidates its token immediately.

### Flow (phone with screen)

1. Phone generates an Ed25519 keypair and sends its public key on register
2. Phone displays a 6-digit code (also shown in its notification)
3. Enter the code in the coordinator web UI or API
4. Coordinator creates the device auth token; the phone fetches it with a
   signed status poll and starts serving inference for the coordinator

### Flow (headless phone)

Headless phones (no screen) follow the same flow but skip the code. The
operator approves the pairing directly from the coordinator UI or API.

### API

```bash
# List pending pairings
curl http://coordinator:8080/api/v1/pair/pending

# Confirm a pairing (provide the phone's device_id + code)
curl -X POST http://coordinator:8080/api/v1/pair/confirm \
  -H "Content-Type: application/json" \
  -d '{"device_id": "...", "code": "123456"}'

# For headless phones, auto-approve:
curl -X POST http://coordinator:8080/api/v1/pair/confirm \
  -H "Content-Type: application/json" \
  -d '{"device_id": "..."}'

# List paired devices
curl http://coordinator:8080/api/v1/pair/paired

# Unpair a device
curl -X POST http://coordinator:8080/api/v1/pair/unpair \
  -H "Content-Type: application/json" \
  -d '{"device_id": "..."}'
```

---

## Using the API

### OpenAI-Compatible Endpoints

```
POST /v1/chat/completions   — Chat completions (streaming + non-streaming)
GET  /v1/models              — List available models
```

### Cluster Management

```
GET  /api/v1/cluster/health     — Cluster health summary
GET  /api/v1/cluster/nodes      — List registered phones (?group=N to filter)
GET  /api/v1/cluster/preflight  — Pre-flight readiness (?group=N to filter)
```

### Event Log

```
GET  /api/v1/events?limit=100   — Query event log
```

### Auth Status

```
GET  /api/v1/auth/status        — Current auth mode
```

All protected endpoints accept authentication via:
- **OIDC:** `Authorization: Bearer <JWT>`
- **PSK:** `Authorization: Bearer <PSK>` or `X-Phonon-Token: <PSK>`

### Example: Streaming Completion

```bash
curl -N http://coordinator:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-3-12b-it-q4_k_m.gguf",
    "messages": [{"role": "user", "content": "Write a haiku about phones."}],
    "stream": true
  }'
```

---

## Authentication

Phonon supports three auth modes:

| Mode | Use Case | Configuration |
|---|---|---|
| `none` | Development / trusted LAN | `mode: "none"` |
| `psk` | Small deployments without OIDC | `mode: "psk"` + `psk: "your-secret"` or `PHONON_PSK` env var |
| `oidc` | Production / multi-operator | `mode: "oidc"` + `issuer` + `client_id` |

### PSK Mode

The pre-shared key can be sent as `Authorization: Bearer <psk>` or
`X-Phonon-Token: <psk>`. Comparison is constant-time.

Set via YAML (`cluster.auth.psk`) or environment variable (`PHONON_PSK`).
The env var takes precedence over YAML.

### OIDC Mode

Configure an OIDC provider (Authentik, Keycloak, Google, etc.):

```yaml
cluster:
  auth:
    mode: "oidc"
    issuer: "https://auth.example.com/application/o/phonon/"
    client_id: "phonon-coordinator"
```

The coordinator performs OIDC discovery to fetch the JWKS URI and validates
incoming Bearer tokens using go-oidc. JWKS keys are cached and refreshed
automatically.

---

## Troubleshooting

### Phone not appearing

1. Check the sidecar is running: the notification should show "Phonon Sidecar"
2. Verify phones and coordinator are on the same subnet
3. Check firewall allows mDNS (UDP 5353) and port 8080
4. Try setting `discovery.mdns.disabled: true` and specify static IPs

### Model push failing

1. Check the download URL is accessible from the coordinator
2. Verify the phone has enough free storage
3. Check the WebSocket connection: `GET /api/v1/cluster/nodes` should show
   the phone as `online`
4. For large models (>2 GB), the push happens in chunks; check
   `/api/v1/events` for progress logs

### Inference returning errors

1. Check model is loaded: `GET /api/v1/cluster/nodes` shows `model_loaded`
2. Check phone health: battery level, temperature, queue depth
3. Verify the phone's sidecar port (9876) is accessible from the coordinator

### Authentication failures

1. For OIDC: verify `issuer` and `client_id` are correct; check JWKS URI
   resolves from the coordinator
2. For PSK: verify the key matches between coordinator and client
3. Check `GET /api/v1/auth/status` to confirm the expected mode

---

## Architecture

```
┌─────────────┐     ┌──────────────────┐     ┌──────────────┐
│             │     │   Coordinator    │     │              │
│  LiteLLM /  │────▶│  (Go binary)     │────▶│  Phone pool  │
│  Agent      │     │  :8080           │     │  (6 Pixels)  │
│  Framework  │     │  REST + WS + UI  │     │  5W each     │
│             │     └──────┬───────────┘     └──────────────┘
└─────────────┘            │
                           │ mDNS + WebSocket + REST
                           │
                    ┌──────┴──────┐
                    │  Sidecar    │
                    │  (Kotlin    │
                    │   APK)      │
                    └─────────────┘
```

### Components

| Layer | Language | Location | Role |
|---|---|---|---|
| **Coordinator** | Go | `cmd/`, `internal/` | API server, routing, model cache, health monitoring, group config |
| **Sidecar** | Kotlin | `sidecar/` | Android foreground service: mDNS, model download, inference, telemetry |

### Key Design Decisions

- **Zero cloud dependencies** — All communication is LAN-only after initial
  model downloads
- **No Google Play Services** — Works on GrapheneOS and other de-Googled ROMs
- **Pool mode** for fast parallel inference (one request per phone, NPU-accelerated)
- **Shard mode** (experimental): large models split across multiple phones
  (Phase 2, CPU via prima.cpp)

---

## Documentation

| Document | What it covers |
|---|---|
| [PHONON.md](PHONON.md) | Full product spec, performance estimates, roadmap |
| [SPEC.md](SPEC.md) | Technical implementation specification |
| [HARDWARE_SETUP.md](docs/HARDWARE_SETUP.md) | Phone selection guide |
| [NPU_ACCELERATION.md](docs/NPU_ACCELERATION.md) | NPU/GPU/CPU acceleration configuration |
| [GRAPHEMEOS_SETUP.md](docs/GRAPHEMEOS_SETUP.md) | GrapheneOS-specific setup |
| [CI_AND_RELEASE.md](docs/CI_AND_RELEASE.md) | Build and release workflow |
| `phonon.example.yaml` | Annotated configuration reference |

---

## License

MIT — see [LICENSE](LICENSE). Code of Conduct in [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

**Phonon** — *Because phones are plentiful, inference should be private, and
the best hardware is the hardware you already own.*
