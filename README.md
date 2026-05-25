# Phonon

**Used phones → unified AI inference.** One endpoint, zero marginal cost.

Phonon turns a handful of cracked-screen Pixels into a private, local inference backend. Install the APK, plug in power and ethernet, point your agent framework at the endpoint. The coordinator handles discovery, health monitoring, load balancing, and model management. You get a cluster that behaves like one API.

**Fast tier** — pool of independent phones with NPU acceleration. Gemma 4 E2B at 25–40 tok/s per phone. Handles tool calls, classification, summarization. You talk to it like any cloud API — models pick themselves based on complexity.

**Reasoning tier** — phones sharding a 27B+ model across 3–4 devices. CPU via prima.cpp pipelined-ring parallelism. 3–8 tok/s. For planning, complex reasoning, multi-step decomposition.

**Zero marginal cost** — no rate limits, no billing, no one reading your prompts. The agent can make fifty calls to reason through a problem without cost pressure. The cluster handles the 95% of inference that doesn't need frontier capability.

**Six phones, $300–600 total, 25–50W power draw.** The gap between "hit a cloud API" and "run inference yourself" dropped from $1,000+ to phone-scrap prices.

---

## Architecture

Two components:

**The coordinator** — a single Go binary. Discovery, pairing, health monitoring, request routing, web UI. Runs on a Pi, NAS, laptop, or Docker. One binary, one port.

**The phone app** — a single Kotlin APK. Always a worker. Runs the inference engine (OlliteRT for pool mode, prima.cpp for shard mode) and a sidecar that handles mDNS announcement, health telemetry, and coordinator commands.

```
┌─────────────┐     ┌──────────────────┐     ┌──────────────┐
│             │     │   Coordinator    │     │              │
│  LiteLLM /  │────▶│  (Pi / NAS /    │────▶│  Phone pool  │
│  Agent      │     │   Docker)       │     │  (6 Pixels)  │
│  Framework  │     │  :8080          │     │              │
│             │     │  REST + WS + UI │     │  5W each     │
└─────────────┘     └──────────────────┘     └──────────────┘
```

Phones announce themselves on the LAN via mDNS. The coordinator discovers them. Pairing is zero-touch — broadcast a token, phones auto-pair within a time window. Add a phone by plugging it in.

The coordinator continuously monitors each phone's battery, temperature, queue depth, and liveness. Overheating phones are removed from the routing pool and re-entered after cooling. Offline phones trigger standby promotion. Automatic, no operator intervention.

---

## Quick Start

1. **Prepare phones.** Enable USB Debugging, disable battery optimization for the Phonon app. One-time step, done over ADB.
2. **Install the APK** on each phone via `adb install phonon-worker.apk`
3. **Start the coordinator** — `./phonon-coordinator` or Docker
4. **Open the web UI** — discovered phones appear. Pair them in one click.
5. **Drag phones into groups**, pick models for each, hit deploy.
6. **Copy the API endpoint** into your agent framework's config.

From first APK install to working inference: ~5 minutes.

---

## Current Status

Phase 1 (pool mode) is in active development. Go coordinator builds, YAML config parses, nodes register and report health. Working toward a usable alpha.

| Layer | Status |
|---|---|
| Go coordinator scaffold | ✅ Done |
| YAML config parser | ✅ Done |
| Node registry | ✅ Done |
| Coordinator-sidecar protocol (REST + WebSocket) | ✅ Done |
| Health monitoring (battery/thermal hysteresis, offline detection, Prometheus metrics) | ✅ Done |
| mDNS discovery | 🔜 In progress |
| Event log (SQLite) | 🔜 Next |
| OIDC authentication | 🔜 Next |
| Inference routing (/v1/chat/completions) | 🔜 Next |
| Sidecar Kotlin APK | 🔜 Next |
| Web UI (React embedded in Go binary) | 🔜 Next |
| Shard mode (prima.cpp) | Phase 2 |

---

## License

Source-available non-commercial license (TBD — likely AGPL or similar). Upstream dependencies are Apache 2.0 or MIT.

---

## Why

The models are ready. Gemma 4 E2B does multimodal inference in under 2 GB. OlliteRT ships a pre-built APK. LiteLLM routes to any OpenAI-compatible endpoint. What's missing is pure orchestration: install an APK on a handful of phones, they self-organize into a compute pool, one endpoint appears on your network.

Phonon is that missing piece. The phones are already in the trash. The software is the rack.

*Built for people who'd rather spend $300 on used phones than $1,000 on a GPU.*
