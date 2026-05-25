# Phonon — Project Plan

## What Phonon Is

Phonon is an orchestration layer that turns a cluster of used Android phones into a unified, managed AI inference backend. It presents a single OpenAI-compatible API endpoint to any client — agent frameworks, home automation platforms, scripts, or anything that speaks the OpenAI API. The client doesn't know or care that the backend is a cluster of cracked Pixels in a rack on a shelf.

Phonon is infrastructure software. It does not perform inference. It coordinates devices that perform inference, routes requests to them, monitors their health, and presents them as a single service.

## What Phonon Is Not

Phonon is not an inference engine, a model runtime, an agent framework, a home automation platform, or a voice assistant. It consumes inference engines (OlliteRT, prima.cpp, llama.cpp) as dependencies. It exposes endpoints that agent frameworks and automation platforms consume. It stays in its lane: discovery, routing, health, configuration.

## Why Phonon Exists

The gap between "hit a cloud API" and "run inference yourself" currently costs $1,000+ minimum (a desktop GPU or a Mac Studio). Used phones with cracked screens cost $50–100 each, draw 5–10W, have 6–12 GB of RAM, NPUs capable of accelerating inference, and built-in batteries that act as per-node UPS. A cluster of six phones aggregates 36–48 GB of usable memory at a total cost of $300–600 and a power draw of 25–50W.

The models are ready. Gemma 4 E2B runs multimodal inference (text, vision, audio) in under 2 GB. The inference engines are ready. OlliteRT ships a pre-built APK that turns any Android phone into an OpenAI-compatible inference server. The agent frameworks are ready. OpenClaw, Home Assistant, and dozens of others consume OpenAI-compatible endpoints.

The missing piece is pure orchestration: install an APK on a handful of phones, they self-organize into a compute pool, one endpoint appears on your network, your agent framework points at it and gets local, private, zero-cost inference. Phonon is that missing piece.

## Target Hardware

Phonon targets used Android phones, with Pixel devices as the primary development and testing platform. The phones that work best are those with the least resale value — cracked screens, cosmetic damage, carrier-locked units — because their compute is essentially free.

Minimum viable phone: 8 GB RAM, ARM64 CPU, Android 14+, USB-C. Recommended: Pixel 7a and above (Tensor G2+), 8–12 GB RAM, which provides NPU acceleration via LiteRT.

A six-phone cluster is the reference configuration for development and documentation. The architecture supports any number of phones — 3, 12, 20, or more.

## Device Preparation

Phones in a Phonon cluster may have cracked, partially unresponsive, or completely non-functional screens. The entire device preparation flow is designed to work over ADB from a laptop, with the screen needed only once for initial setup — and even that can be bypassed in some cases.

### One-Time Setup (requires screen or workaround)

Each phone needs three things configured before it can join a cluster. These are one-time steps that persist across reboots and app installs:

1. **Enable Developer Options.** Settings → About Phone → tap "Build number" seven times.
2. **Enable USB Debugging.** Settings → Developer Options → toggle USB Debugging on.
3. **Disable battery optimization for Phonon.** This prevents Android from killing the inference service. Can be done via ADB after the APK is installed: `adb shell cmd appops set com.phonon.worker RUN_IN_BACKGROUND allow`

Steps 1 and 2 require a functional touchscreen (or a workaround — see below). Once USB debugging is enabled, all subsequent interaction with the phone happens over ADB or over the network. The screen never needs to be touched again.

### Workarounds for Non-Functional Screens

If the screen is completely dead or touch is entirely unresponsive:

- **USB-C to HDMI/DisplayPort + USB mouse.** Many Pixels support DisplayPort alt mode over USB-C. Connect an external display and a USB mouse (via a USB-C hub) to navigate Settings and enable USB debugging visually. This works on Pixel 5a and above.
- **USB OTG mouse.** If the display is cracked but still visible, a USB OTG mouse provides a cursor for navigating the UI when touch zones are dead. A simple USB-C to USB-A adapter and any mouse will work.
- **Pre-configured phones.** If buying a batch of used phones, enable USB debugging on each one while the screen still works, before relegating it to the cluster. This is the recommended practice — configure first, rack second.
- **scrcpy.** Once USB debugging is enabled (even if it was enabled before the screen broke), scrcpy mirrors the phone's screen to a laptop and allows full touch interaction via mouse. This is the most powerful tool for managing a phone with a dead screen, but it requires USB debugging to already be on.

### APK Installation

With USB debugging enabled, APK installation and all subsequent setup is done over ADB from the operator's laptop:

```
# Install the APK
adb install phonon-worker.apk

# Launch the app (starts the foreground service)
adb shell am start -n com.phonon.worker/.MainActivity

# Grant required permissions (if not auto-granted)
adb shell pm grant com.phonon.worker android.permission.FOREGROUND_SERVICE
adb shell pm grant com.phonon.worker android.permission.WAKE_LOCK

# Disable battery optimization
adb shell cmd appops set com.phonon.worker RUN_IN_BACKGROUND allow

# Optional: set a static coordinator URL (bypasses mDNS discovery)
# Useful for OEMs that kill mDNS listeners or for networks where mDNS is unreliable
adb shell "echo 'coordinator_url=http://192.168.1.100:8080' > /data/data/com.phonon.worker/files/phonon.conf"

# Verify the service is running
adb shell dumpsys activity services | grep phonon
```

For multi-phone setup, ADB commands can be scripted. If multiple phones are connected via a USB hub, `adb -s <serial>` targets a specific device. The documentation should include a setup script that automates the full preparation sequence for a batch of phones.

### Network Connectivity

After initial ADB setup, phones join the cluster over the network (Wi-Fi or Ethernet). The phone does not need to remain connected to the laptop via USB after preparation is complete. Power the phone via any USB-C charger, or via a USB-C Ethernet adapter with power passthrough.

### Factory Reset Recommendation

Used phones may have unknown apps, accounts, and configurations from previous owners. A factory reset before joining the cluster is strongly recommended for both security and reliability. The reset can be triggered over ADB if the screen is non-functional:

```
# WARNING: This erases all data on the phone
adb shell recovery --wipe_data
```

After a factory reset, the phone boots to the setup wizard. The minimum path through the wizard (skip Google account, skip Wi-Fi for now, skip everything optional) can be navigated via USB OTG mouse or external display if the screen is dead. Once past the wizard, enable Developer Options and USB Debugging, then proceed with ADB installation as above.

## Architecture

### Two Components

Phonon consists of exactly two components:

**The coordinator.** A single Go binary that serves three functions: discovery and pairing (finds phones on the LAN, establishes trust), cluster management (assigns phones to groups, monitors health, manages model lifecycle), and request routing (exposes the unified API endpoint, routes to the appropriate group, load-balances, streams responses). The coordinator also serves the web UI — a React frontend embedded in the Go binary via `go:embed`. No separate frontend deployment. One binary, one port, serves the API and the UI.

**The phone app.** A single APK installed on every phone. The phone is always a worker node. The phone runs two sub-components: the inference engine (OlliteRT in pool mode, prima.cpp in shard mode) and the cluster sidecar (a lightweight service that handles mDNS announcement, coordinator pairing, health telemetry, and control commands).

The coordinator runs on a Raspberry Pi, a laptop, a NAS, a home server, or any device on the network that can run a Go binary or a Docker container. The coordinator does not run on a phone. This is a deliberate simplification — running a Go binary with embedded React assets inside an Android app via gomobile or Termux introduces significant complexity for a marginal convenience. Phones are workers; the coordinator runs on a more reliable host.

### Sidecar Architecture

The sidecar architecture is critical for long-term maintainability. The inference engine (OlliteRT, prima.cpp) is a dependency, not a fork. The sidecar communicates with it over localhost HTTP or IPC. When OlliteRT updates, the new version drops in without touching the cluster-awareness code. Small, upstreamable patches to OlliteRT (model lifecycle callbacks, queue depth metrics) are contributed upstream. If any upstream dependency dies or changes direction, only the adapter layer in the sidecar needs to be rewritten.

The coordinator is owned code. The sidecar is owned code. Everything underneath (OlliteRT, LiteRT-LM, prima.cpp, llama.cpp) is consumed but not owned.

### Coordinator-Sidecar Protocol

The coordinator and sidecars communicate over two channels:

**REST API (sidecar → coordinator).** The sidecar initiates all routine communication with the coordinator via HTTP REST calls. This includes: registration on startup, periodic health heartbeats (battery, thermal, storage, loaded model, queue depth), model status announcements (what's loaded, what's cached), and pairing handshake responses. REST is stateless and survives reconnection cleanly — if the coordinator restarts, sidecars re-register and resume heartbeats without special recovery logic.

**WebSocket (coordinator → sidecar).** The coordinator maintains a persistent WebSocket connection to each paired sidecar for pushing commands that require immediate action: model push initiation, load/unload commands, mode changes (pool to shard), standby promotion, and graceful shutdown. The WebSocket gives the coordinator a push channel without requiring sidecars to poll for pending commands. If the WebSocket connection drops (coordinator restart, network interruption), the sidecar reconnects and the coordinator re-sends any pending commands.

The sidecar communicates with its local inference engine (OlliteRT, prima.cpp) over localhost HTTP or IPC. This is a separate interface from the coordinator-sidecar protocol — the sidecar translates between coordinator commands and inference engine operations.

### Node Configurations

The cluster supports three node configurations simultaneously:

**Pool mode (independent nodes).** Each phone runs its own model and handles requests independently. The coordinator load-balances across them using health-aware routing. Best for parallel throughput on small, fast models. A five-phone pool running Gemma 4 E2B provides 75–200 tok/s aggregate throughput on parallel requests, with each request completing at full NPU speed (25–40 tok/s on a Pixel 9).

**Shard mode (shard groups).** Multiple phones collectively run a single model too large for any one device. Prima.cpp's pipelined-ring parallelism assigns each phone a slice of the model's layers. Each phone processes its assigned layers and passes activations to the next phone in the ring. The coordinator routes requests to the group's master node. Best for capability — running 27B–70B models that wouldn't fit on one phone.

**Standby nodes.** Phones designated as hot spares for a shard group. The full model file is pre-distributed to standby nodes during initial configuration. If a node in the group fails, a standby is promoted automatically. The operator defines redundancy; the coordinator executes it.

These configurations coexist. A twelve-phone cluster might have four independent pool nodes running Gemma 4 E2B for fast tool calls, three phones sharding a 27B reasoning model, three phones sharding a 27B code model, and two standby nodes — all managed by one coordinator, all presented through one API endpoint.

### Multi-Tier Routing

The architecture enables a multi-tier inference strategy within a single cluster:

**Fast tier (pool).** Small models (Gemma 4 E2B, Qwen 3.5 2B, Phi-4 Mini) on independent phones with NPU acceleration. 25–60 tok/s per phone. Handles tool calls, classification, summarization, simple Q&A, and routine agent tasks. Competitive with cloud API latency on short responses due to zero network round-trip to a datacenter.

**Reasoning tier (shard group).** Larger models (Gemma 4 26B MoE, Gemma 4 31B Dense, QwQ-32B) sharded across 3–4 phones via prima.cpp. 3–8 tok/s. Handles complex reasoning, planning, nuanced writing, and multi-step problem decomposition.

**Capability tier (shard group).** The largest models (Llama 3.3 70B, DeepSeek-R1-70B) sharded across 6–8 phones. 0.5–1.5 tok/s. For batch workloads, overnight processing, and tasks where quality matters more than speed.

The agent framework selects the model per-step based on task complexity. LiteLLM or any OpenAI-compatible router maps model names to Phonon's endpoint. The coordinator routes to the appropriate group internally. Claude or another frontier cloud API sits behind LiteLLM as a fallback for the hardest problems — the cluster handles the 95% of calls that don't need frontier capability.

The natural pattern that emerges: the reasoning tier plans the work, the fast tier executes the tool calls, the reasoning tier synthesizes the results. The big model thinks; the small models act.

### Declarative Configuration

The cluster topology is defined in a single YAML file (`phonon.yaml`). The web UI reads and writes this file. Either interface produces the same running state. The operator defines the topology; the coordinator enforces it. No dynamic scheduling, no runtime rearrangement. The configuration changes when the operator changes it.

```yaml
cluster:
  name: "homelab-inference"
  auth:
    mode: oidc  # or "none" for insecure mode
    issuer: "https://auth.example.com/realms/homelab"
    client_id: "phonon-cluster"

  networking:
    prefer: ethernet  # ethernet | wifi

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

### Discovery and Pairing

Discovery operates in two modes:

**mDNS auto-discovery (default).** Phones announce themselves on the LAN via mDNS. The coordinator discovers them automatically. New phones appear in the web UI as unpaired, displaying their device name, model, and IP address. This is the zero-configuration path and works reliably on Pixels and GrapheneOS.

**Manual registration (fallback).** The coordinator web UI provides an "Add node manually" option where the operator enters a phone's IP address directly. This bypasses mDNS entirely and exists because some Android OEMs (Samsung, Xiaomi, OnePlus) aggressively kill background mDNS/NSD listeners, making auto-discovery unreliable on those devices. Manual registration produces the same paired state as auto-discovery — the coordinator connects to the sidecar at the given IP and proceeds with the same pairing handshake.

The sidecar also accepts a static coordinator URL via a configuration file that can be pushed to the phone during device preparation over ADB. When configured, the sidecar connects directly to the coordinator on startup instead of waiting to be discovered. This provides a second fallback path that doesn't depend on mDNS in either direction.

Pairing is initiated entirely from the coordinator's web UI, not from the phone. This is a critical design decision: the phones in a Phonon cluster may have cracked, partially unresponsive, or completely non-functional screens. The pairing flow must not require any touch interaction on the phone beyond initial APK installation.

In secure mode, the operator clicks "Start pairing" in the coordinator UI, which opens a configurable time window (default: 60 seconds). During this window, the coordinator broadcasts a one-time pairing token via mDNS. Any Phonon sidecar that discovers the token automatically responds with a pairing handshake. For manually registered nodes or nodes with a static coordinator URL, the coordinator sends the pairing token directly to the sidecar over the established connection. The coordinator UI shows each phone as it pairs, and the operator clicks "Accept" or "Reject" for each one. The pairing exchange establishes mutual trust and sets up an encrypted tunnel for all subsequent control-plane communication. No QR codes, no code entry, no touch interaction on the phone required.

During pairing, the sidecar runs a lightweight device audit and reports the results to the coordinator: number of installed packages, root/superuser detection, bootloader lock state, and Android version. The coordinator displays this as advisory information in the UI — for example, "This phone has 142 apps installed; consider factory resetting for a clean inference node." The audit is informational, not a gate — it never blocks pairing.

In insecure mode, pairing is automatic with no time window and no accept/reject step. Suitable for isolated networks or development.

### Request Routing

The coordinator exposes a single OpenAI-compatible API endpoint (`/v1/chat/completions`, `/v1/models`, etc.). Incoming requests specify a model name. The coordinator maps the model name to a group:

- For pool groups: round-robin load-balancing across healthy nodes, skipping overheating, low-battery, or offline phones.
- For shard groups: forward to the group's master node.
- Responses are streamed back to the client via SSE.

### Health Monitoring

The coordinator monitors each phone via the sidecar's health telemetry:

- Battery level, charging state, and battery health (cycle count, capacity percentage via Android BatteryManager)
- Thermal state (SoC temperature)
- Available storage
- Loaded model and its version
- Inference queue depth
- Network interface (ethernet vs Wi-Fi)
- Liveness (heartbeat)

Battery health is surfaced in the UI so the operator can identify phones with degraded batteries. Phones with significantly degraded batteries should not be relied on as UPS-capable nodes — the coordinator marks them as "charger-dependent" when battery health drops below a configurable threshold.

Phones that are overheating are temporarily removed from the routing pool and re-entered after cooling. Phones that go offline in a shard group trigger standby promotion if a standby is configured.

Health data is exposed via a Prometheus metrics endpoint for integration with Grafana or any monitoring stack.

### Model Management

The coordinator acts as the central model cache and distribution point for the cluster, using a declarative reconciliation pattern:

**Desired state** is defined in the YAML configuration: each group specifies a model name. **Current state** is reported by each sidecar: which model is loaded, which models are cached in local storage, and how much free storage remains. The coordinator continuously compares desired state against current state and issues commands to close the gap. This is the same reconciliation loop used by Kubernetes controllers — resilient to reboots (phone comes back, announces what it has, coordinator reconciles), operator changes (YAML is updated, coordinator rolls out the delta), and partial failures (one phone fails to download, coordinator retries without affecting the rest of the group).

The reconciliation flow:

- The coordinator downloads models from upstream sources (HuggingFace, Ollama registry) and caches them locally on the coordinator's storage. When a phone needs a model, the coordinator pushes it to the phone over the LAN. Each model is downloaded from the internet once, regardless of how many phones need it, and coordinator-to-phone transfers happen at LAN speed. Phones never download models from the internet directly.
- Transfers are resumable (HTTP range requests), checksummed (integrity verification after transfer), and retriable (the coordinator retries failed transfers without operator intervention).
- When a sidecar announces its current state (e.g., "I have Gemma 4 E2B loaded, Qwen 3.5 2B cached, 38 GB free"), the coordinator compares against the desired state for that phone's group. If the desired model is already loaded, no action. If it's cached but not loaded, the coordinator issues a load command. If it's not present, the coordinator pushes the model file to the phone followed by a load command.
- When a group's model is changed in the configuration, the coordinator performs a rolling update — reconciling phones one at a time so the group remains available throughout the transition.
- In shard mode with prima.cpp, each phone in the ring needs the full model file (prima.cpp uses mmap and its Halda scheduler determines at runtime which layers each device processes). The coordinator pushes the complete model to each phone in the shard group.

## Security Model

### Two Modes

**Secure mode (default).** The coordinator's API endpoint validates JWTs against a configured OIDC provider (Keycloak, Authentik, Authelia, Zitadel, or any standards-compliant issuer). Node pairing requires a handshake. All control-plane traffic is encrypted. Individual phones only accept commands from their paired coordinator. Clients authenticate with bearer tokens scoped to specific groups or models.

**Insecure mode.** The API is open. Pairing is automatic. All traffic is plaintext. The UI makes it unambiguously clear which mode is active.

In secure mode, individual phones do not accept inference requests directly from the network. They only respond to their paired coordinator, preventing bypass of the auth layer.

## Networking

### Wired (Recommended)

USB-C to Ethernet adapters provide reliable, low-latency connectivity. Two tiers:

- **2.5 GbE (recommended).** USB-C adapters $15–25 each, 2.5 GbE switch $30–50. Most phones' USB-C ports are USB 3.1 Gen 1 (5 Gbps), which drives 2.5 GbE at full speed. A six-phone cluster costs roughly $120–200 in adapters and a switch.
- **1 GbE (adequate).** USB-C adapters $10–15 each, Gigabit switch $15–20. Budget option. Fine for pool mode. Adequate for shard mode because inter-node data (hidden state activations) is small — a few KB to a few hundred KB per token. Compute is the bottleneck, not network bandwidth.

### Wi-Fi

Pool mode over Wi-Fi works well — each request is independent and latency-tolerant. Shard mode over Wi-Fi is viable (prima.cpp is explicitly designed to tolerate Wi-Fi) but unpredictable — depends on router quality, signal, interference, and band. For consistent shard mode performance, wired connections are strongly recommended.

The coordinator detects and displays which network interface each phone is using (green for ethernet, yellow for Wi-Fi).

### Shard Mode Network Overhead

Between each phone in the ring, only intermediate hidden state activations are transmitted — a few KB per token. On 1 GbE, each hop adds ~0.5–1 ms latency. For a six-phone ring, total network latency per token is ~3–6 ms on wired, compared to 700–900 ms of compute time per token. Network overhead is approximately 1% of total latency on wired, 3–5% on Wi-Fi.

## Performance Estimates

### Pool Mode (Phase 1, NPU-Accelerated)

| Configuration | Per-Phone Speed | Aggregate Throughput | Feel |
|---|---|---|---|
| 1 Pixel 9 (Tensor G4) | 25–40 tok/s | 25–40 tok/s | Fast single conversation |
| 1 Pixel 7a (Tensor G2) | 15–25 tok/s | 15–25 tok/s | Slightly slower |
| 5 mixed Pixels | 15–40 each | 75–200 tok/s parallel | Multiple simultaneous conversations with headroom |

Pool mode throughput is parallel — each request gets one phone's full NPU speed. A five-phone pool handles five requests simultaneously at full speed each.

### Pool Mode Model Options

| Model | Size | Per-Phone tok/s (Pixel 9) | Strengths |
|---|---|---|---|
| Gemma 4 E2B | ~2 GB | 25–40 | Multimodal (text, vision, audio), function calling, 128K context. Default workhorse. |
| Gemma 4 E4B | ~4 GB | 10–20 | Better reasoning than E2B, same multimodal support. For 12 GB phones. |
| Qwen 3.5 2B | ~1.5 GB | 20–35 | 262K context window, 200+ languages. Long-context specialist. |
| Qwen 3.5 0.8B | ~500 MB | 40–60 | Ultra-fast, minimal footprint. Maximum throughput for simple tasks. |
| Phi-4 Mini 3.8B | ~3 GB | 15–25 | Strong reasoning and math. Matches 8B models on benchmarks. |
| Qwen 2.5 1.5B | ~1.5 GB | 40–60 | Fast general-purpose. |

### Shard Mode (Phase 2, CPU via prima.cpp)

| Model | Q4 Size | Phones Needed (~7 GB usable each) | Estimated tok/s | Use Case |
|---|---|---|---|---|
| Gemma 4 26B MoE (~3.8B active) | ~14–16 GB | 3 phones | 5–8 | Strong reasoning, only 3.8B params active per token |
| QwQ-32B / DeepSeek-R1-32B | ~18 GB | 3 phones | 4–7 | Complex reasoning, planning |
| Gemma 4 31B Dense | ~18–20 GB | 3–4 phones | 3–5 | High-quality generation |
| Llama 3.3 70B | ~40 GB | 6–7 phones | 0.5–1.5 | Maximum capability, batch work |
| 100B+ class | ~55–70 GB | 8–12 phones | <1 | Overnight/batch only |

Shard mode estimates are extrapolated from prima.cpp's published benchmarks (which included desktop GPUs) with a 0.5–0.7× multiplier for phone-only clusters.

Not all model architectures shard equally well. Standard dense transformers with sequential layers and small hidden states are ideal for pipeline parallelism. Models with unconventional architectures — particularly Mixture-of-Experts models with all-to-all gating patterns — may require more cross-node communication and shard less efficiently. The recommended shard mode models (Gemma 4 31B Dense, QwQ-32B, Llama 3.3 70B) should be tested and benchmarked on real phone hardware, with results published in documentation. MoE models like Gemma 4 26B A4B should be tested separately, with explicit notes on whether MoE routing interacts well with prima.cpp's pipeline parallelism.

### Thermal Throttling

Under sustained load: ~10–20% performance drop after 5 minutes, ~30–40% after 15 minutes, steady state at 60–70% of peak. Physical cooling (vertical rack with passive airflow) significantly extends the thermal envelope. For typical agent workloads (short bursts of 50–500 tokens), thermal throttling is rarely hit.

### Competitive Reference

| Service | Speed | Cost |
|---|---|---|
| OpenAI GPT-4o (streaming) | ~50–100 tok/s perceived | Per-token billing |
| 5-phone Phonon pool (Gemma 4 E2B) | 75–200 tok/s aggregate | Zero marginal cost |
| Ollama on Mac Mini M4 (7B) | 30–60 tok/s | ~$600 hardware |
| Ollama on Raspberry Pi 5 (7B) | 3–8 tok/s | ~$100 hardware |

## Technology Stack

| Component | Technology | Rationale |
|---|---|---|
| Coordinator | Go | Single static binary, cross-compiles to ARM64, no runtime deps. The language of the infrastructure tools the target audience trusts (Traefik, Prometheus, Caddy). |
| Web UI | React + Tailwind, embedded via `go:embed` | No separate frontend deployment. |
| Phone app | Kotlin | Native Android, first-class LiteRT-LM SDK support. |
| Pool mode inference | OlliteRT (LiteRT-LM runtime) | Pre-built APK, NPU acceleration, OpenAI-compatible API. Consumed as a dependency via sidecar. |
| Shard mode inference | prima.cpp | Pipeline parallelism designed for home clusters with heterogeneous devices and Wi-Fi tolerance. MIT licensed. |
| Shard mode fallback | llama.cpp RPC | Mature, widely tested. Fallback if prima.cpp integration proves problematic. MIT licensed. |

## Dependency and Fork Strategy

| Component | Relationship | Strategy |
|---|---|---|
| Coordinator | Owned code (greenfield) | No existing project does this. Own it entirely. |
| Phone app sidecar | Owned code (greenfield) | Cluster-awareness, health telemetry, coordinator control API. |
| OlliteRT | Dependency (Git submodule / Gradle module) | No source modifications. Integration via localhost HTTP. Small upstream PRs for hooks where needed. If rejected, maintain minimal patch set. |
| prima.cpp | Runtime dependency | Packaged and invoked in shard mode. No modifications. |
| llama.cpp | Runtime dependency | Foundation layer. No modifications. |
| LiteRT / LiteRT-LM | Runtime dependency | Google's on-device ML runtime. No modifications. |

## Operational Concerns

### Android Background Killing

Android aggressively kills background services to save battery. The phone app must run as a foreground service with a persistent notification. Users should disable battery optimization for the app. The coordinator monitors node liveness via heartbeats and alerts the operator if a phone becomes unresponsive.

This is the single most likely source of ongoing operational issues. The problem is not just standard Android battery optimization — individual OEMs layer their own aggressive killing behavior on top:

- Samsung: "Sleeping apps" list re-adds apps periodically; separate battery management ignores standard Android exemptions.
- Xiaomi: Hidden "autostart" permission required; MIUI battery saver kills foreground services.
- OnePlus: Separate battery optimization system that overrides Android defaults.
- Huawei/Honor: Most aggressive OEM killing; some devices kill foreground services even with all exemptions granted.

Phonon 1.0 targets Pixels running stock Android or GrapheneOS, where foreground service behavior is predictable and standard exemptions work. These OEMs do not add proprietary battery killing layers. This narrows the supported device list but produces a reliable 1.0.

OEM-specific workarounds (Samsung, Xiaomi, OnePlus, etc.) are a community contribution opportunity. The canonical reference is dontkillmyapp.com. As the project gains adoption, users with non-Pixel devices can contribute per-OEM documentation and test results. The architecture does not change — the workarounds are device configuration steps, not code changes.

### Storage Awareness

Cracked-screen phones may have 64 or 128 GB of storage. The coordinator tracks available storage per phone and warns before assigning a model that won't fit.

In pool mode, each phone stores the complete model file for its assigned model — typically 1.5–4 GB for the small models suited to pool mode.

In shard mode with prima.cpp, each phone in the ring stores the full quantized model file. Prima.cpp uses mmap and its Halda scheduler determines at runtime which layers each device processes, so every node needs the complete file. This means a 40 GB quantized 70B model requires 40 GB of free storage on each of the 6–7 phones in the shard group — not 40 GB divided across them. This is the primary storage constraint for shard mode and significantly affects which phones are viable for large model configurations. A phone with 64 GB total storage and ~20 GB consumed by Android may only have ~40 GB free, which is barely sufficient for a 70B Q4 model with no room for additional cached models. 128 GB phones are strongly preferred for shard groups running large models.

Standby nodes must also store the full model file (pre-distributed during initial configuration) so they can join the ring without a cold download when promoted.

### Fault Tolerance

- **Pool mode:** Coordinator detects offline nodes via health checks and stops routing. Requests retry on healthy nodes.
- **Shard mode:** A failed node breaks the pipeline. If a standby is configured, it's promoted automatically (full model file already cached). If no standby, requests to that group fail with an error and the client retries or falls back.

Redundancy is a first-class citizen but the operator's responsibility to configure. The system provides the tools; it does not make assumptions about how much redundancy is appropriate.

### Coordinator Deployment

The coordinator is a Go binary with three pieces of persistent state: the YAML configuration file, the event log (SQLite database), and the model cache directory. All runtime state — which phones are online, their health, their loaded models — is ephemeral and reconstructed from sidecar heartbeats within seconds of a coordinator restart.

This makes the coordinator a standard container workload. The recommended deployment is Docker or Podman with `restart: always`, or Kubernetes with replicas. The YAML config, event log database, and model cache directory are mounted volumes. If the coordinator process dies, the container runtime restarts it and the cluster recovers automatically as sidecars re-establish heartbeats.

The coordinator does not implement built-in failover or leader election. High availability is the deployment platform's responsibility, not the application's. Run it behind a reverse proxy for TLS termination and load balancing if desired.

### Battery Safety

Used phones may have degraded batteries. Sustained inference load generates heat that accelerates battery degradation. The coordinator surfaces battery health data so the operator can identify at-risk devices. Documentation should include guidance on periodic physical inspection of batteries for swelling, and a recommendation to remove phones with visibly damaged batteries from the cluster.

### Silent Degradation

A phone that is alive but producing degraded output (from extreme thermal throttling, memory pressure, or hardware faults) is harder to detect than a dead phone. The coordinator's thermal monitoring and automatic pool removal is the first line of defense — phones throttling beyond a configurable threshold are pulled from rotation. More sophisticated output-quality validation (test prompts, output distribution comparison) is a potential 1.x feature but is not in 1.0 scope.

## Release Plan

### 1.0 Scope

Version 1.0 ships two phases that together constitute a complete, production-usable product.

#### Phase 1 — Pool Mode (Alpha)

Each phone runs independently with LiteRT-LM and NPU acceleration via OlliteRT. The coordinator discovers phones, manages configuration, routes requests, serves the web UI, handles OIDC. The unified API endpoint works.

Deliverables:

- Coordinator binary (Go, cross-compiled for amd64 and arm64) and Docker image
- Phone APK with sidecar and OlliteRT integration
- Web UI with phone tiles (status, battery, temperature, network, queue depth), group management, drag-and-drop assignment, API endpoint display
- Declarative YAML configuration
- mDNS discovery and zero-touch pairing (mDNS token broadcast + click-to-accept in coordinator UI, with device audit)
- Model cache on the coordinator: models downloaded once from upstream, distributed to phones over LAN
- Health-aware round-robin routing across pool nodes
- Prometheus metrics endpoint
- OIDC authentication (optional, with insecure mode)
- Rolling model updates
- Event log: the coordinator persists a timeline of all cluster events (node joined, left, overheated, cooled, model loaded, model update failed, pairing completed, errors) in a local SQLite database, queryable through the web UI. This is the "what happened while I was asleep" feature that turns a toy into infrastructure.
- Documentation: setup guide, hardware recommendations, model selection guide, battery safety guidance

This is a usable alpha with real value. Parallel throughput across multiple phones, health-aware routing, declarative configuration. It validates the coordinator architecture, the sidecar design, the pairing flow, and the UX.

Estimated effort: 2–4 months for a motivated solo developer.

#### Phase 2 — Shard Mode via prima.cpp (Beta)

Phones in a shard group participate in prima.cpp's pipelined-ring parallelism. Each node is assigned a slice of the model's layers and passes activations to the next node in sequence. CPU-only (no NPU acceleration). Enables models too large for any single phone.

Deliverables:

- Shard group configuration in YAML (`mode: shard`)
- prima.cpp integration in the phone app (native NDK build, packaged as a shared library invoked by the sidecar)
- Coordinator manages shard group topology, standby node promotion, model distribution to all nodes in the group
- Mixed configurations: pool groups and shard groups coexisting
- Network interface awareness for shard group assignment recommendations
- Standby node pre-provisioning and automatic promotion
- Documentation: shard mode setup, model-to-phone sizing guide, networking recommendations

The user experience: change a group's mode from `pool` to `shard` in the YAML or UI, the coordinator reconfigures the phones.

Estimated effort: 2–3 months additional, heavily dependent on testing across real hardware.

#### 1.0 Release

Phase 1 + Phase 2 shipped together (or Phase 1 as alpha, Phase 2 as beta, then 1.0 when both are stable). This is a complete product: pool mode for fast parallel throughput, shard mode for large model capability, mixed configurations, health management, declarative config, web UI, authenticated API.

### 1.x — NPU-Accelerated Sharding (Speculative)

Replace the prima.cpp CPU sharding backend with a custom orchestration layer built on raw LiteRT. Each phone's NPU runs its slice of the model at full speed. This is the feature that would make a phone cluster genuinely competitive with more expensive hardware.

This is not on the 1.0 roadmap. It ships if and when one of the following occurs:

- An ML runtime expert joins the project as a collaborator.
- An upstream project (LiteRT, prima.cpp, or another runtime) implements the necessary graph-slicing and cross-device activation-passing primitives.
- Academic research produces a reference implementation that can be adapted.

The core technical challenges are: slicing transformer graphs into per-phone NPU-compatible subgraphs, managing distributed KV cache across devices, serializing intermediate activations between NPU memory spaces over the network, and handling heterogeneous NPU capabilities across different phone models.

If feasible, estimated timeline from proof-of-concept to production: 12–18 months.

### 2.0 — Sensor Mesh (Future)

Phones have cameras, microphones, GPS, accelerometers, and other sensors. Version 2.0 exposes these as addressable endpoints on the network. The coordinator does not interpret sensor data — it presents the raw capabilities over a clean API surface for other systems (Home Assistant, Node-RED, custom automation) to consume.

Potential capabilities:

- Camera feeds exposed as endpoints (snapshots, video streams)
- Microphone endpoints for speech-to-text (leveraging Gemma 4 E2B's native audio support or dedicated Whisper instances)
- GPS/location endpoints
- Environmental sensors (light, proximity, barometer)
- Phone-as-wake-word-listener for voice assistant pipelines

2.0 scope is deliberately undefined at this stage. The architecture decisions in 1.0 (sidecar pattern, coordinator-as-registry, declarative config) are designed to accommodate sensor endpoints without architectural changes — a sensor is just another capability the sidecar reports and the coordinator makes addressable.

## Use Cases Enabled by 1.0

### Agent Backend

The primary use case. An agent framework (OpenClaw, custom, or any framework that speaks the OpenAI API) points at Phonon's endpoint. The pool tier handles fast tool calls, classification, and routine reasoning at NPU speed. The shard tier handles complex planning and multi-step reasoning. The agent can think as long as it wants — the marginal cost is zero. No rate limits, no billing, no one reading the prompts. The agent can make fifty calls to reason through a problem without cost pressure.

### Multi-Model Routing

Different groups run different models optimized for different tasks. The agent framework selects the model per-step: Gemma 4 E2B for tool calls, Gemma 4 26B for planning, a Qwen coder variant for code generation. LiteLLM maps model names to Phonon's endpoint alongside cloud APIs, creating a unified routing layer where the phone cluster handles routine work and the cloud handles frontier tasks.

### Home Assistant Integration

Phonon's endpoint plugs directly into Home Assistant's OpenAI-compatible conversation agent integration. Combined with Gemma 4 E2B's native audio support, a phone in each room can serve as a private, local voice assistant — listen, transcribe, reason, and respond without any data leaving the network. A private Alexa replacement.

### Private Code Assistant

A 27B code model (Qwen Coder, Qwen 3.5-27B) sharded across 3–4 phones provides autocomplete and code chat. Slower than Copilot but private — proprietary code never leaves the network. The pool tier handles fast autocomplete suggestions while the shard tier handles "explain this function" or "refactor this module."

### Batch Processing

Queue documents, emails, notes, or any corpus for overnight processing. At 3–8 tok/s on the shard tier, the cluster processes enormous volumes if real-time response isn't needed. Nobody does this with cloud APIs because of cost. Nobody does it with a single phone because of time. A cluster doing batch work overnight is a genuinely new capability for an individual.

### RAG Knowledge Base

Index personal documents, notes, and files into a local vector store. Retrieval pulls relevant chunks, the cluster reasons over them. The "unlimited inference" angle means the RAG pipeline can be aggressive about retrieval, re-ranking, and multi-hop reasoning because calls are free. A private alternative to Notion AI or NotebookLM with no data leaving the house.

### Deployable Offline Backend

A phone cluster in a Pelican case with a portable router and a battery bank is a deployable AI backend for disaster response, field research, humanitarian work, or remote locations. No internet required. The architecture is inherently offline-capable.

## Licensing

All upstream dependencies are license-clean for open-source distribution and commercial use:

| Component | License |
|---|---|
| OlliteRT | Apache 2.0 |
| LiteRT / LiteRT-LM | Apache 2.0 |
| prima.cpp | MIT |
| llama.cpp | MIT |
| Gemma 4 model weights | Apache 2.0 |
| Qwen 3.5 model weights | Apache 2.0 |

cake is explicitly excluded due to its FAIR license (requires commercial agreement for business use).

License for Phonon itself is undecided. Options:

- **Apache 2.0:** Maximizes adoption. Anyone can use, embed, or build on the project.
- **AGPL 3.0:** Prevents cloud providers or competitors from taking the project and selling it as a service without contributing back. More protective but potentially limits adoption.
- **Hybrid:** AGPL for the coordinator, Apache 2.0 for the phone app and client libraries.

### Google Play Services Dependency Audit

Phonon must not depend on Google Play Services. The target audience includes users running degoogled phones (GrapheneOS, CalyxOS, LineageOS without GApps). All upstream dependencies must be audited for Google Play Services requirements before inclusion:

- **LiteRT / LiteRT-LM:** Some LiteRT features historically depended on Google Play Services for GPU delegate initialization. Verify that the CPU and NPU paths used by OlliteRT work without Play Services. Test on a clean GrapheneOS install without sandboxed Play Services early in development.
- **OlliteRT:** Check whether OlliteRT's APK bundles any Play Services dependencies (Firebase, Google Analytics, Play Core). If it does, either contribute upstream patches to remove them or maintain a de-googled build variant.
- **Model downloads:** The coordinator handles model downloads, so phones don't need internet access or any Google services for model acquisition.

If any dependency proves incompatible with degoogled Android, document the limitation and explore alternatives. Play Services compatibility is a hard requirement, not a nice-to-have.

## Distribution

Phonon will not be distributed through the Google Play Store. Play Store policies on persistent background services, large downloads, and sideloaded components create unnecessary friction for an app whose entire purpose is to run a persistent inference server.

First-class distribution channels:

- **F-Droid:** Primary distribution channel. F-Droid's policies are friendly to apps that run background services, the audience overlaps heavily with the self-hosting community, and the reproducible build process builds trust. The APK must be buildable from source with no proprietary dependencies — enforced by the Google Play Services audit above.
- **Obtainium:** Direct-from-GitHub APK distribution. Users point Obtainium at the Phonon GitHub repository and receive automatic updates from GitHub Releases. Zero infrastructure cost, zero review process, immediate availability of new releases.
- **Manual sideload:** APK published as a GitHub Release artifact. The baseline distribution method that always works.

The coordinator is distributed as a Go binary (GitHub Releases) and a Docker image (Docker Hub / GitHub Container Registry).

## Revenue Opportunity (Optional)

The software is open-source. A potential revenue opportunity exists in physical hardware, but this is a nice-to-have, not a core business requirement. Phonon is primarily a personal project that solves its author's problem; commercial viability is not a design constraint.

If pursued, hardware options include cooling racks and cases (3D-printed or injection-molded racks that hold phones vertically with passive airflow channels, USB-C pass-through for power and Ethernet, and cable management) and cluster kits (rack + USB-C Ethernet adapters + 2.5 GbE switch + USB power hub + documentation — everything except the phones).

## First-Run Experience

The target experience from prepared phones to working inference backend:

0. **Prepare phones (one-time).** Enable USB debugging, factory reset (recommended), install the Phonon APK via ADB, launch the app. See the Device Preparation section for detailed steps including workarounds for non-functional screens. This is a one-time cost per phone — once prepared, the phone never needs to be touched again.
1. Run the coordinator on a Pi, laptop, NAS, or server (`docker run phonon` or download the binary).
2. Open the web UI from any browser on the network.
3. See discovered phones listed as unpaired.
4. Click "Start pairing." Phones auto-pair within the time window. Accept each phone in the UI.
5. Drag phones into groups, select models for each group.
6. Hit deploy. The coordinator downloads models from upstream, caches them, and pushes them to phones over the LAN.
7. Copy the API endpoint URL into your agent framework's config.

Target time: five minutes from prepared phones to working inference backend (device preparation is additional, varies by phone condition). Zero touch interaction on the phones after initial preparation.

## Future Considerations (Not in 1.0 Scope)

The following ideas have been evaluated and deferred. They are recorded here to inform future planning without committing to timelines.

**Node trust scoring (1.x).** A system where the coordinator periodically sends known test prompts to nodes and compares outputs against a golden reference to detect silent degradation. Useful but complex. Thermal monitoring in 1.0 is the first line of defense; trust scoring is a refinement.

**Pre-flight shard profiling (1.x).** Before activating a shard group, the coordinator runs a lightweight communication benchmark across the target phones to predict throughput and warn if the model is a bad fit for the topology. Useful for operators experimenting with unfamiliar model architectures.

**Sensor mesh (2.0).** Exposing phone sensors (camera, microphone, GPS, accelerometers) as addressable endpoints on the network. The coordinator presents the raw capabilities; other systems (Home Assistant, Node-RED) consume them. The sidecar architecture supports this without changes to the coordinator or inference path.

**Physics-inspired UI design language (ongoing).** The phonon metaphor (crystalline lattice, vibrating nodes, collective excitation) could inspire a distinctive dashboard aesthetic. Not a functional requirement, but a design direction that differentiates Phonon from generic infrastructure UIs.

**Hosted management service (future revenue).** An optional cloud relay that allows remote coordinator management, configuration push, and model recommendations based on hardware profiles. A potential recurring revenue stream that doesn't require restrictive software licensing. The 1.0 architecture (web UI, API, stateless coordinator) doesn't preclude this.

**Distributed agent fabric (3.0+).** Phones have sensors, inference, storage, and connectivity. The coordinator could accept "task" definitions (trigger + prompt + destination) and push agentic workloads to individual phones. A phone in the kitchen watches for package deliveries; a phone at the desk monitors a codebase. This is a different project built on the same infrastructure. Far-future peripheral vision only.

## Summary

| What | Status |
|---|---|
| Single phone as inference server | Works today (OlliteRT, Ollama via Termux) |
| Independent phones behind a manual router | Works today (manual LiteLLM/nginx config) |
| Declarative phone cluster with unified API | **Phonon 1.0 — Phase 1** |
| Shard mode (model across multiple phones, CPU) | **Phonon 1.0 — Phase 2** |
| NPU-accelerated sharding | **Phonon 1.x — speculative** |
| Battery/thermal-aware cluster management | **Phonon 1.0 — Phase 1** |
| OIDC-authenticated inference API | **Phonon 1.0 — Phase 1** |
| Sensor mesh (camera, mic, GPS endpoints) | **Phonon 2.0 — future** |
