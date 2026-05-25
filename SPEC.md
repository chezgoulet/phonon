# Phonon — Specification

> **Status:** DRAFT
> **Derived from:** PHONON.md (project plan) + CONTRIBUTING.md (engineering standards)
> **Last updated:** 2026-05-25

---

## Table of Contents

1. [Scope and Goals](#1-scope-and-goals)
2. [System Architecture](#2-system-architecture)
3. [Coordinator Specification](#3-coordinator-specification)
4. [Sidecar Specification](#4-sidecar-specification)
5. [Coordinator-Sidecar Protocol](#5-coordinator-sidecar-protocol)
6. [OpenAI-Compatible API](#6-openai-compatible-api)
7. [Pairing and Discovery](#7-pairing-and-discovery)
8. [Model Management](#8-model-management)
9. [Health Monitoring](#9-health-monitoring)
10. [Configuration Format](#10-configuration-format)
11. [Web UI](#11-web-ui)
12. [Security Model](#12-security-model)
13. [Release Plan](#13-release-plan)
14. [Engineering Standards](#14-engineering-standards)
15. [Open Questions](#15-open-questions)

---

## 1. Scope and Goals

### 1.1 What Phonon Does

Phonon is an orchestration layer that turns a cluster of used Android phones into a unified, managed AI inference backend. It presents a single OpenAI-compatible API endpoint. The client does not know or care that the backend is a phone cluster.

### 1.2 What Phonon Is Not

Phonon is not an inference engine, a model runtime, an agent framework, a home automation platform, or a voice assistant. It consumes inference engines (OlliteRT, prima.cpp, llama.cpp) as dependencies. It stays in its lane: discovery, routing, health, configuration.

### 1.3 Design Principles

1. **Phones are workers.** The coordinator never runs on a phone. This avoids gomobile/Termux complexity.
2. **No touch on phones.** Pairing, configuration, and all ongoing operations are done from the coordinator's web UI or via ADB. Phones with cracked/non-functional screens are first-class targets.
3. **No Play Services.** The project targets degoogled users (GrapheneOS, CalyxOS, LineageOS). All dependencies must be audited for Play Services requirements.
4. **Declarative over imperative.** The YAML configuration defines desired state. The coordinator continuously reconciles toward it.
5. **Consume, don't fork.** Inference engines (OlliteRT, prima.cpp, llama.cpp) are upstream dependencies integrated over localhost HTTP or IPC. Small upstreamable patches are contributed; anything larger means adapter rewrites, not forks.
6. **Narrow 1.0 beats wide 1.0.** Phase 1 targets Pixels running stock Android or GrapheneOS, where foreground service behavior and mDNS are predictable.

### 1.4 Target Hardware

- **Minimum:** 8 GB RAM, ARM64 CPU, Android 14+, USB-C
- **Recommended:** Pixel 7a+ (Tensor G2+), 8–12 GB RAM (NPU acceleration via LiteRT)
- **Reference config:** 6 phones. Architecture supports 3–20+.
- **Storage requirements:** 128 GB strongly preferred for shard groups. 64 GB is barely sufficient for a single Q4 70B model.

---

## 2. System Architecture

### 2.1 Two-Component Model

```
┌─────────────────────────────┐
│      Coordinator (Go)       │
│  ┌───────────────────────┐  │
│  │   Web UI (React/TW)   │  │  ← Browser
│  │   go:embed            │  │
│  ├───────────────────────┤  │
│  │   API                 │  │  ← Clients (agents, HA, scripts)
│  │   /v1/chat/completions│  │     OpenAI-compatible
│  │   /v1/models          │  │
│  ├───────────────────────┤  │
│  │   Discovery/Pairing   │  │  ← mDNS + manual + static URL
│  ├───────────────────────┤  │
│  │   Cluster Management  │  │  ← Reconciliation loop
│  │   Model Cache/Distrib │  │
│  ├───────────────────────┤  │
│  │   Event Log (SQLite)  │  │  ← Persistent timeline
│  └───────────────────────┘  │
│        │   REST + WebSocket  │
└────────┼─────────────────────┘
         │
    ┌────┴──────────┬──────────┐
    │               │          │
┌───┴──────┐  ┌────┴─────┐  ┌─┴────────┐
│ Phone 1  │  │ Phone 2  │  │ Phone N  │
│ Sidecar  │  │ Sidecar  │  │ Sidecar  │
│ Engine   │  │ Engine   │  │ Engine   │
└──────────┘  └──────────┘  └──────────┘
```

### 2.2 Component Boundaries

| Component | Technology | Build Output | Deployment |
|---|---|---|---|
| Coordinator | Go 1.23+ | Static binary for amd64 + arm64 | Docker or native, Pi/NAS/laptop/server |
| Phone app | Kotlin (JDK 21) | APK (targetSdk 35, minSdk 26) | F-Droid / Obtainium / sideload |
| Web UI | React + Tailwind + TypeScript | Embedded in coordinator via `go:embed` | None — served by coordinator |

### 2.3 Dependencies (Consumed, Not Owned)

| Dependency | Purpose | Integration |
|---|---|---|
| OlliteRT | Pool mode inference, NPU-accelerated via LiteRT-LM | Localhost HTTP over sidecar |
| prima.cpp | Shard mode, CPU-based pipeline parallelism (Phase 2) | NDK shared library invoked by sidecar |
| llama.cpp RPC | Shard mode fallback (Phase 2) | Runtime process |
| LiteRT / LiteRT-LM | Google's on-device ML runtime | Used by OlliteRT, not called directly |

---

## 3. Coordinator Specification

### 3.1 Project Layout

```
coordinator/
  cmd/
    phonon-coordinator/   # main.go — small, wires dependencies
  internal/
    config/               # YAML parsing, validation, Phonon types
    discovery/            # mDNS listener, phone discovery
    registry/             # Node registry, state management
    routing/              # Request routing, load balancing
    model/                # Model cache, distribution, reconciliation
    api/                  # HTTP handlers, OpenAI-compatible API
    auth/                 # OIDC validation
    web/                  # Embedded React UI assets (go:embed)
    health/               # Health checks, Prometheus metrics
  pkg/
    phonon/               # Shared types (if any become public)
  .golangci.yml
  go.mod
  go.sum
```

### 3.2 Persistent State

Three pieces of persistent state, all mounted as volumes in container deployments:

1. **YAML configuration file** (`phonon.yaml`) — declarative cluster topology. Read and written by the web UI.
2. **Event log** (SQLite database) — timeline of all cluster events: node joined, left, overheated, cooled, model loaded, model update failed, pairing completed, errors. Queryable through the web UI.
3. **Model cache directory** — downloaded models stored on coordinator filesystem. Organized by model name/version. Phones pull models from here over LAN.

All runtime state (node health, loaded models, queue depths) is ephemeral and reconstructed from sidecar heartbeats within seconds of a coordinator restart.

### 3.3 Compilation Constraints

- Zero CGo dependencies. Must compile to a static binary.
- Cross-compile to amd64 and arm64.
- Prefer stdlib over external packages for HTTP, JSON, crypto, and net.

### 3.4 Testing Requirements

- Table-driven tests for all `internal/` packages.
- Coverage targets: `internal/config/`, `internal/routing/`, `internal/registry/`: ≥70%. HTTP handlers: ≥50%.
- `go test -race` must pass on every PR.
- Mock the sidecar interface — never connect to a real phone during unit tests.

### 3.5 Coordinator Deployment

Standard container workload. Docker/Podman with `restart: always` or Kubernetes with replicas. Three mounted volumes: config, event log, model cache. No built-in failover or leader election — HA is the deployment platform's responsibility.

---

## 4. Sidecar Specification

### 4.1 Project Layout

```
sidecar/src/main/java/com/phonon/worker/
  sidecar/              # Cluster awareness, pairing, health telemetry
    SidecarService.kt   # Foreground service, heartbeat loop
    PairingManager.kt   # mDNS announcement + pairing handshake
    HealthReporter.kt   # Battery, thermal, storage, queue depth
    ControlServer.kt    # HTTP server for coordinator commands
    ConfigManager.kt    # Local config file management (phonon.conf)
  inference/            # Adapter layer for inference engines
    InferenceEngine.kt  # Interface/abstraction
    OlliteRTAdapter.kt  # OlliteRT over localhost HTTP
    PrimaAdapter.kt     # prima.cpp via NDK bridge (Phase 2)
  model/                # Model lifecycle
    ModelManager.kt     # Download, verify, load, unload
  network/
    MdnsAnnouncer.kt    # mDNS service announcement (NSD)
  util/
    Extensions.kt       # Android API extensions
    Preconditions.kt    # Defensive checks
```

### 4.2 Android Specifics

- **Foreground service** with persistent notification (required by Android 14+).
- `targetSdk 35` (Android 15), `minSdk 26` (Android 8).
- Use Jetpack libraries (lifecycle, navigation, Room if needed).
- **No Google Play Services dependencies.** Hard requirement. Build-time verification must confirm zero Play Services linkage.
- Coroutines for all async work. Raw threads prohibited.

### 4.3 Local Configuration

The sidecar reads a `phonon.conf` file at startup from `Context.getFilesDir()`. This file can be pushed via ADB during device preparation:

```
coordinator_url=http://192.168.1.100:8080
```

If present, the sidecar connects directly to the coordinator on startup instead of waiting for mDNS discovery. This provides a fallback for OEMs that kill mDNS listeners.

### 4.4 Inference Engine Integration

The sidecar communicates with the inference engine (OlliteRT, later prima.cpp) over **localhost HTTP or IPC**. This is a separate interface from the coordinator-sidecar protocol. The sidecar translates between coordinator commands and inference engine operations.

- **Pool mode (Phase 1):** OlliteRT via localhost HTTP. OlliteRT is a pre-built APK consumed as a dependency. The sidecar sends inference requests to OlliteRT's local API and returns responses.
- **Shard mode (Phase 2):** prima.cpp via NDK bridge. The sidecar invokes prima.cpp as a shared library, which handles pipelined-ring parallelism across phones.

---

## 5. Coordinator-Sidecar Protocol

### 5.1 Two-Channel Architecture

| Channel | Direction | Transport | Purpose |
|---|---|---|---|
| REST API | Sidecar → Coordinator | HTTP | Registration, heartbeats, model status, pairing responses |
| WebSocket | Coordinator → Sidecar | Persistent WS | Push commands: model push, load/unload, mode change, standby promotion |

### 5.2 REST API (Sidecar → Coordinator)

All REST calls are initiated by the sidecar. Stateless — survives coordinator restarts.

**Registration (on startup):**
```
POST /api/v1/sidecar/register
{
  "device_id": "pixel-7a-01",
  "device_model": "Pixel 7a",
  "android_version": "15",
  "ip_address": "192.168.1.42",
  "network_interface": "eth0"
}
```

**Health heartbeat (every 15 seconds):**
```
POST /api/v1/sidecar/heartbeat
{
  "device_id": "pixel-7a-01",
  "battery": { "level": 85, "charging": true, "cycles": 320, "capacity_pct": 92 },
  "thermal": { "soc_temp_c": 42 },
  "storage": { "total_gb": 128, "free_gb": 68 },
  "model": { "loaded": "gemma-4-E2B-it", "cached": ["gemma-4-E2B-it", "qwen-3.5-2b"] },
  "queue_depth": 2,
  "network": "eth0",
  "timestamp": "..."
}
```

**Model status update (on change):**
```
POST /api/v1/sidecar/model-status
{
  "device_id": "pixel-7a-01",
  "loaded": null,
  "cached": ["gemma-4-E2B-it"],
  "free_gb": 42
}
```

**Pairing response:**
```
POST /api/v1/sidecar/pair
{
  "device_id": "pixel-7a-01",
  "token": "abc123...",
  "audit": {
    "packages_installed": 12,
    "root_detected": false,
    "bootloader_locked": true,
    "android_version": "15"
  }
}
```

### 5.3 WebSocket (Coordinator → Sidecar)

Persistent connection maintained by coordinator. On disconnect, sidecar reconnects; coordinator re-sends pending commands.

**Model push:**
```json
{
  "type": "model_push",
  "payload": {
    "model": "gemma-4-E2B-it",
    "url": "http://coordinator:8080/api/v1/models/gemma-4-E2B-it/download",
    "checksum": "sha256:abc...",
    "size_bytes": 2000000000
  }
}
```

**Model load:**
```json
{
  "type": "model_load",
  "payload": { "model": "gemma-4-E2B-it" }
}
```

**Model unload:**
```json
{
  "type": "model_unload",
  "payload": {}
}
```

**Mode change:**
```json
{
  "type": "mode_change",
  "payload": { "mode": "pool", "runtime": "litert" }
}
```

**Standby promotion:**
```json
{
  "type": "standby_promote",
  "payload": {
    "model": "gemma-4-27b-q4",
    "url": "http://coordinator:8080/api/v1/models/gemma-4-27b-q4/download",
    "checksum": "sha256:def..."
  }
}
```

**Graceful shutdown:**
```json
{
  "type": "shutdown",
  "payload": { "reason": "operator_removed" }
}
```

---

## 6. OpenAI-Compatible API

### 6.1 Endpoints

The coordinator exposes these endpoints on its HTTP port:

| Endpoint | Method | Description |
|---|---|---|
| `/v1/chat/completions` | POST | Inference request. `model` field in body maps to a group. |
| `/v1/models` | GET | List available models (model names from all configured groups). |
| `/v1/completions` | POST | Text completions (if supported by engine). |
| `/health` | GET | Coordinator health. |
| `/metrics` | GET | Prometheus metrics. |

### 6.2 Request Routing Logic

1. Extract `model` from request body.
2. Look up group by model name in configuration.
3. If **pool group**: select a healthy node using round-robin, skipping overheating, low-battery, or offline nodes. Forward request to that phone's OlliteRT endpoint.
4. If **shard group**: forward to the group's master node. The master node coordinates the ring internally.
5. If model not found: return 404 with an error message listing available models.
6. If no healthy nodes available in the group: return 503.
7. Responses are streamed back to the client via SSE.

### 6.3 Model Name to Group Mapping

The coordinator maintains an in-memory mapping: `model_name → group`. This mapping is rebuilt from the YAML configuration on change. The web UI displays the current mapping.

---

## 7. Pairing and Discovery

### 7.1 Discovery Modes

Three modes, in order of preference:

1. **mDNS auto-discovery (default).** Phones announce themselves via mDNS/NSD. Coordinator discovers and displays them in the web UI as unpaired. Works reliably on Pixels and GrapheneOS.
2. **Manual registration (fallback).** Operator enters a phone's IP address in the coordinator web UI. Bypasses mDNS for OEMs that kill mDNS listeners (Samsung, Xiaomi, OnePlus).
3. **Static coordinator URL.** Sidecar reads `coordinator_url` from `phonon.conf` (pushed via ADB during preparation). Sidecar connects directly on startup — no mDNS in either direction.

### 7.2 Pairing Flow (Secure Mode)

1. Operator clicks "Start pairing" in the coordinator web UI.
2. Coordinator opens a configurable time window (default: 60 seconds).
3. Coordinator broadcasts a one-time pairing token via mDNS.
4. For manually registered/static-URL nodes, coordinator sends the token directly over the established connection.
5. Sidecar discovers the token and responds with a pairing handshake (includes device audit).
6. Coordinator UI shows each phone as it pairs. Operator clicks "Accept" or "Reject" for each.
7. On accept, pairing exchange establishes mutual trust and sets up an encrypted tunnel for all control-plane traffic.
8. No QR codes, no code entry, no touch interaction on the phone.

### 7.3 Device Audit

During pairing, the sidecar reports:
- Number of installed packages
- Root/superuser detection
- Bootloader lock state
- Android version

Displayed as advisory information in the UI. Never blocks pairing.

### 7.4 Insecure Mode

Automatic pairing with no time window, no accept/reject step, no encryption. For isolated networks or development.

---

## 8. Model Management

### 8.1 Declarative Reconciliation

**Desired state:** defined in `phonon.yaml` — each group specifies a model name.

**Current state:** reported by each sidecar — which model is loaded, which are cached, free storage.

**Reconciliation loop:**
1. Sidecar announces current state (heartbeat or model-status event).
2. Coordinator compares against desired state for that phone's group.
3. If desired model is loaded: no action.
4. If desired model is cached but not loaded: issue `model_load` command.
5. If desired model is not present: issue `model_push` (from coordinator cache) followed by `model_load`.
6. Reconciliation runs continuously. Resilient to reboots, operator changes, partial failures.

### 8.2 Model Cache and Distribution

- Coordinator downloads models from upstream (HuggingFace, Ollama registry) and caches them locally.
- Models are distributed from coordinator to phones over LAN. Each model is downloaded from the internet once.
- Transfers are resumable (HTTP range requests), checksummed (SHA-256), and retriable.
- Phones never download models from the internet directly.

### 8.3 Rolling Updates

When a group's model is changed in the configuration, the coordinator reconciles phones one at a time. The group remains available throughout the transition — at least one phone is running the old model until all phones have updated.

### 8.4 Shard Mode Storage

With prima.cpp, each phone in the ring stores the **full quantized model file**. Prima.cpp uses mmap and its Halda scheduler determines at runtime which layers each device processes. A 40 GB Q4 70B model requires 40 GB free on each of 6–7 phones.

---

## 9. Health Monitoring

### 9.1 Telemetry Fields

Reported by sidecar every 15 seconds (heartbeat):

| Field | Source | Used For |
|---|---|---|
| Battery level | Android BatteryManager | UPS awareness, low-battery routing exclusion |
| Charging state | Android BatteryManager | Same |
| Battery cycles | Android BatteryManager | Degradation tracking |
| Battery capacity % | Android BatteryManager | "Charger-dependent" marking below threshold |
| SoC temperature | Android thermal HAL | Overheat pool removal |
| Free storage | Android StatFs | Model assignment guard |
| Loaded model | Sidecar state | Reconciliation input |
| Cached models | Sidecar state | Reconciliation input |
| Inference queue depth | OlliteRT API | Load balancing |
| Network interface | Android ConnectivityManager | Green (eth) / yellow (wifi) display |

### 9.2 Automatic Actions

- **Overheating:** Phone removed from routing pool. Re-entered after cooling below threshold.
- **Low battery + not charging:** Phone removed from pool. Re-entered on charge.
- **Offline in shard group:** Standby promoted automatically if configured. Request fails with error if no standby.
- **Degraded battery:** Phone marked "charger-dependent" in UI below configurable capacity threshold.

### 9.3 Prometheus Metrics

Exposed at `/metrics` on the coordinator:
- `phonon_nodes_online{group="..."}` — gauge
- `phonon_nodes_offline{group="..."}` — gauge
- `phonon_nodes_overheating` — gauge
- `phonon_requests_total{group="...", status="..."}` — counter
- `phonon_request_duration_ms{group="..."}` — histogram
- `phonon_queue_depth{device_id="..."}` — gauge
- `phonon_battery_level{device_id="..."}` — gauge
- `phonon_thermal_temp_c{device_id="..."}` — gauge

---

## 10. Configuration Format

### 10.1 phonon.yaml

```yaml
cluster:
  name: "homelab-inference"

  auth:
    mode: oidc          # "oidc" or "none"
    issuer: "https://auth.example.com/realms/homelab"
    client_id: "phonon-cluster"

  networking:
    prefer: ethernet    # "ethernet" or "wifi"

groups:
  - name: fast-general
    mode: pool
    model: gemma-4-E2B-it
    runtime: litert
    phones: [pixel-7a-01, pixel-7a-02, pixel-8-01, pixel-8-02]

  - name: reasoning
    mode: shard
    model: gemma-4-27b-q4
    runtime: prima
    phones: [pixel-9-01, pixel-9-02, pixel-9-03]
    standby: [pixel-8-02]

  - name: code
    mode: shard
    model: qwen-coder-8b-q4
    runtime: prima
    phones: [pixel-9-04, pixel-9-05]
    standby: [pixel-7a-03]
```

### 10.2 Validation Rules

- Each phone can appear in at most one group.
- Standby nodes cannot also be active nodes in a different group.
- `mode: shard` groups must have `runtime: prima`.
- `mode: pool` groups must have `runtime: litert`.
- At least one phone must be specified per group.
- Model names must be recognized (validated against a known-model list that the coordinator maintains and updates from upstream registries on start).

### 10.3 Phone Identifiers

Phone identifiers are human-friendly names assigned during pairing (the operator names them in the web UI). These names are the keys in the YAML configuration. The coordinator maintains the mapping from human name to device ID (hardware serial) in its internal registry.

---

## 11. Web UI

### 11.1 Component Structure

```
web/src/
  components/
    PhoneTile.tsx       # Phone status card (battery, temp, model, network)
    GroupCard.tsx       # Group configuration editor
    PairingPanel.tsx    # Pairing flow UI (discovered phones, accept/reject)
    ClusterMap.tsx      # Topology visualization
  hooks/
    useNodes.ts         # Node registry API
    useGroups.ts        # Group configuration API
    useHealth.ts        # Health data subscription
    usePairing.ts       # Pairing flow state
  pages/
    Dashboard.tsx       # Main cluster overview
    Config.tsx          # YAML configuration editor
    Settings.tsx        # Coordinator settings
  lib/
    api.ts              # Typed API client
    types.ts            # Shared TypeScript types (mirrors Go schema)
  App.tsx
  main.tsx
```

### 11.2 Dashboard

The main page displays:
- Cluster name and status header.
- Grid of phone tiles, each showing: device name, model status (loaded model / idle / loading / offline), battery level with charging indicator, thermal indicator (green / yellow / red), network icon (green ethernet / yellow wifi), inference queue depth.
- Group cards showing assigned phones, model, mode, and standby nodes.

### 11.3 Pairing Flow

- Section showing discovered but unpaired phones.
- "Start pairing" button. On click, countdown timer appears.
- During the time window, phones appear one by one with Accept/Reject buttons.
- Each phone shows device name, model, IP, and device audit info.

### 11.4 Technology

- React + Tailwind + TypeScript.
- Prettier for formatting, ESLint with `@typescript-eslint/strict-type-checked`.
- React Query for server state, Zustand or context for UI state. No Redux.
- Vitest + React Testing Library for component tests.
- Any is prohibited. Use `unknown` for genuinely unknown types.

---

## 12. Security Model

### 12.1 Two Modes

| Aspect | Secure Mode (default) | Insecure Mode |
|---|---|---|
| API auth | JWT validation against OIDC provider | Open |
| Pairing | Time window + handshake + accept/reject | Automatic |
| Control-plane traffic | Encrypted | Plaintext |
| UI indicator | Clear "secure" badge | Clear "insecure" badge |
| Direct phone access | Denied — phones only accept coordinator commands | Open |

### 12.2 OIDC Configuration

In secure mode, the coordinator validates bearer tokens against a configured OIDC provider. Supported providers: Keycloak, Authentik, Authelia, Zitadel, or any standards-compliant issuer. Clients authenticate with tokens scoped to specific groups or models.

## 13. Release Plan

### 13.1 Phase 1 — Pool Mode (Alpha)

**Deliverables:**

1. **Coordinator binary** — Go, cross-compiled for amd64 and arm64. Docker image.
2. **Phone APK** — Kotlin, with sidecar + OlliteRT integration (pool mode only).
3. **Web UI** — Dashboard with phone tiles, group management, drag-and-drop assignment, API endpoint display.
4. **Declarative YAML configuration** — `phonon.yaml` with validation.
5. **mDNS discovery and zero-touch pairing** — Token broadcast + click-to-accept + device audit.
6. **Model cache** — Coordinator downloads from upstream, distributes to phones over LAN.
7. **Health-aware routing** — Round-robin across pool nodes, skipping unhealthy.
8. **Prometheus metrics** — `/metrics` endpoint.
9. **OIDC authentication** — Optional. Insecure mode also available.
10. **Rolling model updates** — One phone at a time.
11. **Event log** — SQLite, queryable through web UI.
12. **Documentation** — Setup guide, hardware recommendations, model selection guide, battery safety guidance.

**Estimated effort:** 2–4 months for a motivated solo developer.

**Alpha release criteria:**
- 3+ phones in a pool mode group successfully serving inference requests.
- Coordinator recovers from restart without data loss (event log preserved, node state reconstructed from heartbeats).
- Pairing flow works end-to-end without touching the phone screen.
- Model update from one model to another succeeds without dropping all requests.

### 13.2 Phase 2 — Shard Mode via prima.cpp (Beta)

**Deliverables:**

1. **Shard group configuration** in YAML (`mode: shard`).
2. **prima.cpp integration** — Native NDK build, packaged as shared library invoked by sidecar.
3. **Coordinator management** of shard topology, standby promotion, model distribution.
4. **Mixed configurations** — Pool and shard groups coexisting.
5. **Network interface awareness** — Green/yellow indicators, shard group assignment recommendations.
6. **Standby node pre-provisioning and promotion.**
7. **Documentation** — Shard mode setup, model-to-phone sizing guide, networking recommendations.

**Estimated effort:** 2–3 months additional. Heavily dependent on real hardware testing.

**Beta release criteria:**
- 3+ phones in a shard group successfully running a model that doesn't fit on one phone.
- Standby promotion works (node goes down, standby picks up with minimal service interruption).
- Mixed configuration (pool + shard) works simultaneously.

### 13.3 1.0 Release

Phase 1 + Phase 2. A complete product: fast parallel pool mode, large model shard mode, mixed configurations, health management, declarative config, web UI, authenticated API.

### 13.4 Post-1.0

- **1.x — NPU-accelerated sharding (speculative):** Replace prima.cpp CPU backend with custom LiteRT NPU orchestration. 12–18 months if feasible. Requires ML runtime expert or upstream implementation.
- **2.0 — Sensor mesh:** Expose phone sensors as network endpoints. Sidecar architecture supports this without changes to coordinator or inference path.

---

## 14. Engineering Standards

(Defined in detail in CONTRIBUTING.md. Summary here.)

### 14.1 Go Coordinator

- `gofmt` + `go vet` mandatory. `golangci-lint` with: errcheck, govet, ineffassign, staticcheck, misspell, gocritic, whitespace, noctx, unparam, prealloc, errorlint.
- Zero CGo dependencies. Static binary only.
- Error wrapping with `fmt.Errorf("context: %w", err)`. No bare `errors.New` in non-sentinel paths.
- Panic only in `init()` and unrecoverable boot conditions.
- Table-driven tests. Mock the sidecar. `go test -race` must pass.

### 14.2 Kotlin Sidecar

- `ktlint` with default rules. `detekt` with zero-tolerance config.
- Coroutines for all async work. Raw threads prohibited.
- No Google Play Services dependencies. Build-time verification.
- JUnit 5 + MockK for tests. Never call OlliteRT/prima.cpp in unit tests.

### 14.3 React Web UI

- Prettier + ESLint with `@typescript-eslint/strict-type-checked`.
- `strict: true` in tsconfig. `any` prohibited.
- Functional components with hooks. React Query + Zustand. No Redux.
- Vitest + React Testing Library. Playwright for E2E (deferred to pre-release).

### 14.4 CI Pipeline

Three parallel jobs (Go, Kotlin, React). All must pass green before merge. Each job runs: lint → test → build for its layer.

### 14.5 PRs

- Branch naming: `feat/`, `fix/`, `docs/`, `chore/`, `refactor/`, `perf/`, `test/`, `ci/`.
- Conventional commits. PR title becomes squash-merge commit message.
- PR body: what changed and why.
- Reviews required: Go code by robot/vigilant or Christopher; Kotlin and UI by Christopher.

---

## 15. Open Questions

1. **OlliteRT license verification.** The document lists OlliteRT as Apache 2.0 but this should be verified from the actual repo before Phase 1 begins. If it's GPL or has non-commercial restrictions, it constrains how the APK is distributed.
2. **LiteRT NPU path on GrapheneOS.** The Play Services audit flags LiteRT GPU delegate as a potential Play Services dependency. The NPU path (which Phonon relies on for pool mode) must be tested on a clean GrapheneOS install without sandboxed Play Services before Phase 1 ships.
3. **prima.cpp phone-only performance.** Shard mode estimates use a 0.5–0.7× multiplier on prima.cpp's published desktop-GPU benchmarks. The first real phone cluster test will either validate or invalidate these numbers, which affects the shard mode hardware requirements table.
4. **WebSocket reconnection semantics.** On reconnect, the coordinator needs to re-send any pending commands. The spec defines this but the implementation detail (command queue with acknowledgment vs. re-send-everything) needs to be decided during implementation.
5. **Model download from phones behind NAT.** If phones are on a separate VLAN or behind NAT from the coordinator, the model push (coordinator → phone) may require the sidecar to pull from the coordinator rather than the coordinator pushing. The current design assumes LAN adjacency.
6. **Event log schema.** The SQLite schema for the event log is not specified here. It should track: timestamp, event type, device ID, details (JSON blob), and severity. The implementation should decide the exact schema early in Phase 1.
