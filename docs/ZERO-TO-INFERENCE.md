# Phonon — Zero-to-Inference Setup Guide

This walkthrough takes a new operator from bare hardware to the first
inference token, in order. Each stage ends with a **verification gate** — a
command and its expected output — so you can tell success from silence.

For deeper detail on any stage, see the linked documents:

| Document | Covers |
|---|---|
| [HARDWARE_SETUP.md](HARDWARE_SETUP.md) | Phone selection, BOM, racking, power, networking |
| [GRAPHEMEOS_SETUP.md](GRAPHEMEOS_SETUP.md) | Full first-run phone configuration over ADB |
| [PHONE-API.md](PHONE-API.md) | Coordinator ↔ sidecar wire protocol, port configuration |
| [NPU_ACCELERATION.md](NPU_ACCELERATION.md) | Backend selection (NPU/GPU/CPU) per group |
| [../SPEC.md](../SPEC.md) | Technical implementation specification |
| [../PHONON.md](../PHONON.md) | Product spec, architecture, performance estimates |

---

## 1. Prerequisites

### Phones (minimum 1)

- **Android** 12+ (API 31); minimum viable hardware is ARM64, 8 GB RAM,
  USB-C ([SPEC §1.4](../SPEC.md)). Tested on Pixel 6–9 and Moto G Stylus 5G.
- **Recommended:** Pixel 7a or newer (Tensor G2+) for NPU acceleration.
- **Storage:** 16 GB minimum for the APK plus a small model; more for larger
  GGUF files.
- **OS:** stock Android or GrapheneOS. No Google Play Services required.
- One screen-touch session is needed to enable Developer Options unless you
  use a mouse/external display workaround (see
  [PHONON.md → Device Preparation](../PHONON.md)). After that, everything
  happens over ADB or the network.

### Coordinator machine (1)

- Any Linux, macOS, or Windows (WSL2) machine on the same LAN: Raspberry Pi,
  old laptop, NAS, home server.
- Docker (recommended) **or** Go 1.23+ (`go.mod` declares `go 1.23.0`) to
  build from source.
- No GPU, no special hardware. The coordinator does not run on phones.

### Network

- All phones and the coordinator on the **same subnet** (required for mDNS
  discovery).
- Firewall must allow **UDP 5353** (mDNS) and coordinator port **8080**
  (configurable).
- Ethernet adapters recommended for reliability; Wi-Fi works for pool mode.
  See [HARDWARE_SETUP.md → Networking](HARDWARE_SETUP.md).

---

## 2. Coordinator setup

### Build

The coordinator has **no command-line flags**. All configuration comes from
`phonon.yaml` plus environment variables.

Option A — Docker (recommended):

```bash
git clone https://github.com/chezgoulet/phonon && cd phonon
docker compose up -d
```

Option B — from source:

```bash
CGO_ENABLED=0 go build -o phonon-coordinator ./cmd/phonon-coordinator
./phonon-coordinator          # reads ./phonon.yaml from the working directory
```

`make build` produces `bin/phonon-coordinator`; `make cross-build` produces
linux/amd64 and linux/arm64 binaries. To include the web UI assets in the
binary, use `./build.sh` (builds the React frontend first, then embeds it).

### Configure

Copy the annotated reference config and edit it:

```bash
cp phonon.example.yaml phonon.yaml
```

**Important:** a config file with no groups defined is rejected — the
coordinator exits at startup with `at least one group must be defined`. The
example ships with groups commented out, so uncomment and fill in at least
one before starting (or remove `phonon.yaml` entirely to run groupless with
defaults). A present-but-broken config never silently falls back to insecure
defaults; only a *missing* file does.

Minimal working example (fields verified against `internal/config/types.go`
and [SPEC §10](../SPEC.md)):

```yaml
cluster:
  name: "my-cluster"
  auth:
    mode: "none"              # "none", "psk", or "oidc"
  networking:
    prefer: "ethernet"        # "ethernet" or "wifi"
  inference_port: 9876        # must match PHONON_INFERENCE_PORT on the sidecars

groups:
  - name: "fast-general"
    mode: "pool"              # "pool"; "shard" is experimental
    runtime: "litert"         # pool requires litert
    backend: "auto"           # auto | npu | gpu | cpu
    model: "gemma-3-12b-it-q4_k_m.gguf"
    phones: ["phone-kitchen"]
    download_url: "https://huggingface.co/bartowski/gemma-3-12b-it-GGUF/resolve/main/gemma-3-12b-it-Q4_K_M.gguf"
    checksum: "sha256:<digest>"
```

Environment variables (all optional):

| Variable | Overrides | Default |
|---|---|---|
| `PHONON_CONFIG` | Config file path | `phonon.yaml` |
| `PHONON_PORT` | Listen port (only when `cluster.bind` is unset) | `8080` |
| `PHONON_CACHE_DIR` | Model cache directory | `./cache` |
| `PHONON_COORDINATOR_URL` | External URL phones use for model downloads | `http://localhost:<port>` |
| `PHONON_PSK` | PSK auth key (beats YAML value) | — |

Set `PHONON_COORDINATOR_URL` to the coordinator's LAN IP
(e.g. `http://192.168.1.100:8080`) — phones pull model pushes from this URL,
and `localhost` only works when the phone *is* the coordinator host.

### Run

```bash
./phonon-coordinator
```

> **Verification gate:** startup logs are JSON on stdout. You should now see
> (among others):
>
> ```
> msg="pairing manager initialized" ...
> msg="UI served at /ui/"
> msg="inference port configured" port=9876 sidecar_env=PHONON_INFERENCE_PORT
> msg="mDNS discovery started"
> msg="listening" addr=":8080" ...
> ```
>
> The `inference port configured` line is your runtime port check: note the
> `port` value — every sidecar must run with a matching `PHONON_INFERENCE_PORT`.
> See [Troubleshooting](#7-troubleshooting) for what a mismatch looks like.

Smoke-test the HTTP surface:

```bash
curl -s http://localhost:8080/livez
# {"status":"ok","version":"0.1.0"}

curl -s http://localhost:8080/readyz
# {"deps":{"event_log":"ok","pairing_store":"ok"},"status":"ok"}
```

Note: `/health` exists but permanently redirects (HTTP 308) to `/readyz`;
scripts should call `/livez` or `/readyz` directly.

Open `http://<coordinator-ip>:8080/ui/` in a browser — the web dashboard.
(The bare root URL redirects to `/ui/`.)

---

## 3. Phone preparation

This section condenses [GRAPHEMEOS_SETUP.md](GRAPHEMEOS_SETUP.md); follow that
document for troubleshooting each step. Work through one phone fully before
batching the rest.

### 3.1 One-time setup (needs the screen once)

1. Complete the Android setup wizard skipping everything optional (Google
   account, fingerprint, Wi-Fi if using Ethernet).
2. Settings → About phone → tap **Build number** 7×.
3. Settings → System → Developer options → enable **USB debugging**.
4. Connect USB; accept the RSA prompt ("Always allow from this computer").
   Verify: `adb devices` lists the phone.

Cracked/dead screen? See
[PHONON.md → Workarounds for Non-Functional Screens](../PHONON.md): USB-C
display + mouse, USB OTG mouse, or scrcpy.

A factory reset of used phones before clustering is strongly recommended
(`adb shell recovery --wipe_data` erases everything — see
[PHONON.md](../PHONON.md)).

### 3.2 Install the APK

Build or download the sidecar APK, then install over ADB:

```bash
cd sidecar
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

For multiple phones connected via a hub, loop with `adb -s <serial>` (scripted
example in [GRAPHEMEOS_SETUP.md §3](GRAPHEMEOS_SETUP.md)).

### 3.3 Permissions and battery optimization (critical)

Without battery-optimization exclusion, Android will kill the service.

```bash
# Notification permission (Android 13+, needed by the foreground service)
adb shell appops set com.chezgoulet.phonon POST_NOTIFICATIONS allow

# Battery optimization exclusion
adb shell dumpsys deviceidle whitelist +com.chezgoulet.phonon
adb shell cmd appops set com.chezgoulet.phonon RUN_ANY_IN_BACKGROUND allow
```

Also disable Settings → Battery → **Adaptive Battery**, and verify the
whitelist took effect:

```bash
adb shell dumpsys deviceidle | grep -A5 "Whitelist"
# You should see com.chezgoulet.phonon listed
```

Launch the app (it also auto-starts on boot via a `BOOT_COMPLETED` receiver):

```bash
adb shell am start -n com.chezgoulet.phonon/.MainActivity
```

> **Verification gate:** the phone shows a persistent **"Phonon Worker"**
> notification. Over logcat you should see the service connect:
>
> ```bash
> adb logcat -s PhononService:CoordinatorClient
> # Expected: "Connected to coordinator" and periodic heartbeats
> ```

### 3.4 Optional: static coordinator URL (skips mDNS)

If mDNS is unreliable on your network (managed switches blocking multicast,
guest-network isolation, OEMs killing NSD listeners), push a config file so
the sidecar connects directly instead of waiting to be discovered. Format and
location (read by `PhononService` at startup):

```
coordinator_url=http://<coordinator-ip>:8080
```

The file lives in the app's private storage:
`/data/data/com.chezgoulet.phonon/files/phonon.conf`.

```bash
adb shell "echo 'coordinator_url=http://192.168.1.100:8080' \
  > /data/data/com.chezgoulet.phonon/files/phonon.conf"
```

On unrooted devices this write needs either root or a debuggable build
(release builds are minified and not debuggable; the debug variant uses the
application id `com.chezgoulet.phonon.debug`). TODO-[owner]: document the
supported push path for production (non-rooted, release) installs.

You can also disable mDNS entirely for static deployments:
`cluster.discovery.mdns.disabled: true` in `phonon.yaml`.

After preparation, phones only need power and network — no USB tether to the
laptop.

---

## 4. Pairing

Pairing is **enforced**: an unpaired phone refuses all inference requests
(HTTP 403), and the coordinator ignores control traffic from devices without
their pairing token. There is no insecure bypass on the phone side.

What happens automatically when the sidecar starts and reaches the
coordinator:

1. The phone registers itself and appears as **unpaired** in the cluster node
   list.
2. The phone generates an Ed25519 keypair, sends the public key, and receives
   a **6-digit pairing code**, which it displays on its status screen
   (and logs under the `PairingClient` tag).
3. It then polls the pairing-status endpoint, signing each poll, until the
   coordinator confirms — at which point it receives and stores its device
   auth token.

Your part — confirm the pairing from the coordinator side:

```bash
# Find the pending pairing (includes the code to expect)
curl -s http://localhost:8080/api/v1/pair/pending
# [{"device_id":"...","device_model":"Pixel 7a","code":"482913",
#   "ip_address":"192.168.1.42","created_at":"...","expires_at":"..."}]

# Confirm with device_id + code
curl -s -X POST http://localhost:8080/api/v1/pair/confirm \
  -H "Content-Type: application/json" \
  -d '{"device_id": "...", "code": "482913"}'

# Headless phone (no screen to show a code)? Omit the code to auto-approve:
curl -s -X POST http://localhost:8080/api/v1/pair/confirm \
  -H "Content-Type: application/json" \
  -d '{"device_id": "..."}'
```

In the web UI, the **Pairing** tab shows two columns: *Unpaired* phones
(name, model, IP, device ID) and *Active* phones with their state. Pairing
confirmation itself currently happens via the API (the tab displays the
endpoint to use).

> **Verification gate:** within seconds of confirming:
>
> ```bash
> curl -s http://localhost:8080/api/v1/pair/paired     # device listed
> curl -s http://localhost:8080/api/v1/cluster/nodes   # "state":"online"
> ```
>
> Node states progress `unpaired` → `paired` → `online` (once heartbeats
> flow). If stuck at `unpaired`, re-check that the phone shows the same code
> you confirmed, and see [Troubleshooting](#7-troubleshooting).

Repeat per phone. Identifiers like `phone-kitchen` used in group configs map
to these paired devices; the coordinator auto-generates default names from
model + serial suffix, renamable in the UI ([SPEC §10.3](../SPEC.md)).

---

## 5. Model load and first inference

### 5.1 Define the group

With the group from step 2 in `phonon.yaml`, restart (or start) the
coordinator. The model reconciler loops every 30 s: it downloads the model
once into the coordinator cache (`PHONON_CACHE_DIR`), then pushes it to each
phone in the group over the LAN and issues the load command. Phones never
download from the internet directly.

Notes on model sources:

- With an explicit `download_url` (as above), the coordinator fetches from
  there. `checksum` (`sha256:...`) is optional but recommended.
- Without one, the reconciler resolves `<org>/<repo>` names against HuggingFace
  automatically (default quant `Q4_K_M`; override with `<org>/<repo>:<QUANT>`),
  and names on the built-in known-model list are accepted without warning.
  Unknown names *without* a `download_url` produce a validation warning.

Check readiness before inferring:

```bash
curl -s http://localhost:8080/api/v1/cluster/preflight
# {"overall":"ready","groups":{"fast-general":{"status":"ready", ...}}}
```

Watch transfer progress in the event log:

```bash
curl -s "http://localhost:8080/api/v1/events?limit=50"
```

> **Verification gate:** nodes report the loaded model and active backend:
>
> ```bash
> curl -s http://localhost:8080/api/v1/cluster/nodes
> # ... "model_loaded":"gemma-3-12b-it-q4_k_m.gguf", "backend":"npu" ...
> ```
>
> First-time downloads are gigabytes — give the reconciler time. In the UI,
> the phone card's badge moves from *no model* to the model name; the backend
> badge (NPU/GPU/CPU) shows what actually initialized
> ([NPU_ACCELERATION.md](NPU_ACCELERATION.md)).

### 5.2 First token

List models the endpoint will accept:

```bash
curl -s http://localhost:8080/v1/models
```

Send a completion:

```bash
curl -s http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-3-12b-it-q4_k_m.gguf",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'
```

> **Verification gate:** an OpenAI-compatible `chat.completion` object:
>
> ```json
> {
>   "id": "chatcmpl-...",
>   "object": "chat.completion",
>   "choices": [{"message": {"role": "assistant", "content": "..."},
>                "finish_reason": "stop"}],
>   "usage": {...}
> }
> ```
>
> If you get `503 {"error":{"message":"no available phone with model ..."
> "loaded"}}` instead, no node has finished loading yet — re-check 5.1's gate.

Streaming works too (SSE deltas ending with `data: [DONE]`):

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-3-12b-it-q4_k_m.gguf",
    "messages": [{"role": "user", "content": "Write a haiku about phones."}],
    "stream": true
  }'
```

Point LiteLLM, Open WebUI, or any OpenAI-compatible client at
`http://<coordinator-ip>:8080/v1` and you're done.

Auth modes other than `none` require a header on API calls: PSK accepts
`Authorization: Bearer <psk>` or `X-Phonon-Token: <psk>`; OIDC accepts a
bearer JWT. Check current mode with
`curl http://localhost:8080/api/v1/auth/status`.

---

## 6. Verification gates summary

| Stage | Command | Success looks like |
|---|---|---|
| Coordinator up | `curl -s localhost:8080/livez` | `{"status":"ok","version":"0.1.0"}` |
| Dependencies ready | `curl -s localhost:8080/readyz` | `"event_log":"ok"` in deps |
| Port check (#281) | read startup log | `"inference port configured" port=9876` |
| Sidecar alive | `adb logcat -s PhononService:CoordinatorClient` | "Connected to coordinator" + heartbeats |
| Phone visible | `GET /api/v1/cluster/nodes` | entry with `"state"` field |
| Paired & online | `GET /api/v1/cluster/nodes` | `"state":"online"` |
| Model loaded | `GET /api/v1/cluster/nodes` | `"model_loaded":"<name>"` |
| Group ready | `GET /api/v1/cluster/preflight` | `"overall":"ready"` |
| First token | `POST /v1/chat/completions` | `chat.completion` JSON with choices |

---

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Phone never appears in the node list | mDNS blocked or unreliable: UDP 5353 filtered, AP/client isolation, phones on a different subnet than the coordinator | Put both on the same subnet; allow UDP 5353 and TCP 8080; or skip discovery — push a static `coordinator_url` to the phone (§3.4) and/or set `discovery.mdns.disabled: true` |
| Sidecar dies minutes after launch / after screen-off | Battery optimization killed it (the #1 operational failure) | Re-apply §3.3 whitelist commands; disable Adaptive Battery; as a last resort `adb shell svc power stayon true`. Non-Pixel OEMs add their own killers — see dontkillmyapp.com |
| Every phone shows unreachable-for-inference while heartbeats still succeed | Inference-port mismatch: coordinator probes `cluster.inference_port`, sidecars listen on `PHONON_INFERENCE_PORT`; they must match (both default 9876) | Read the runtime check from the startup log — `"inference port configured" port=… sidecar_env=PHONON_INFERENCE_PORT` (added in [#281]) — and set the same value on both sides. Details: [PHONE-API.md → Port configuration](PHONE-API.md) |
| Coordinator exits immediately at startup with `at least one group must be defined` | Config file present but no groups defined (e.g. fresh copy of `phonon.example.yaml`) | Uncomment/define ≥1 group, or delete `phonon.yaml` to run groupless defaults. A broken config refuses to start rather than fail open |
| Inference returns `503 … no available phone with model … loaded` | Model still downloading/pushing, or group `phones:` don't match paired device IDs | Check `GET /api/v1/events` for download progress and `GET /api/v1/cluster/nodes` for `model_loaded`; make sure YAML phone identifiers match paired devices |
| `curl http://host:8080/health` returns 308 | `/health` permanently redirects to `/readyz` | Use `/livez` or `/readyz` directly |
| Model push fails mid-transfer | Download URL unreachable from the coordinator, or phone out of storage | Verify `download_url` from the coordinator host; free up phone storage (each phone in a group stores the complete model file) |

[#281]: https://github.com/chezgoulet/phonon/issues/281

---

## See also

- [README.md](../README.md) — quick start, configuration reference, API summary
- [TEST_PROCEDURES.md](TEST_PROCEDURES.md) — hardware test procedures
- [CI_AND_RELEASE.md](CI_AND_RELEASE.md) — build and release workflow
