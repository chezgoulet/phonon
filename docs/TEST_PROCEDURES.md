# Phonon — End-to-End Test Procedures

> **Status:** DRAFT
> **Version:** 0.2.0 (Sprint A)
> **Target:** Anyone setting up a phonon cluster from scratch and validating it works

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Coordinator Smoke Test](#2-coordinator-smoke-test)
3. [Pairing a Phone (Secure Mode)](#3-pairing-a-phone-secure-mode)
4. [Declarative Model Config](#4-declarative-model-config)
5. [Model Push (Coordinator → Sidecar)](#5-model-push-coordinator--sidecar)
6. [Model Load + Checksum Verification](#6-model-load--checksum-verification)
7. [OpenAI-Compatible Inference](#7-openai-compatible-inference)
8. [Health & Metrics](#8-health--metrics)
9. [Failover & Recovery](#9-failover--recovery)
10. [Security: TLS, CORS, Body Limits](#10-security-tls-cors-body-limits)
11. [Full Sprint A Checklist](#11-full-sprint-a-checklist)

---

## 1. Prerequisites

### Hardware

- 1 Linux machine (coordinator host) — x86_64 or ARM64, Go 1.23+
- 1+ Android phones (Android 14+, ARM64, 8 GB RAM) — Pixel 7a or better
- USB-C Ethernet adapters (optional but recommended for reliable networking)
- Same LAN subnet for all devices

### Software

- Go toolchain (`go version` ≥ 1.23)
- Android Debug Bridge (`adb`) — for initial phone setup
- GrapheneOS or stock Android on phones
- `curl`, `jq` (for API testing)

### Build the Coordinator

```bash
git clone https://github.com/chezgoulet/phonon.git
cd phonon
go build -o phonon-coordinator ./cmd/phonon-coordinator/
```

### Sample Config

Create `phonon.yaml`:

```yaml
coordinator:
  listen: ":9876"  # default port for internal cluster traffic
  data_dir: /var/lib/phonon
  log_level: info

cluster:
  pairing:
    mode: insecure         # secure requires pairing flow; insecure for dev
  groups:
    - name: small-models
      model: gemma-2-2b-it
      pool_size: 2
      device_filter:
        min_ram_gb: 8
    - name: large-models
      model: llama-3.2-3b-it
      pool_size: 2
      download_url: "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
      checksum: "a1b2c3d4e5f6..."  # SHA-256 of the model file
      shard:
        count: 2
        strategy: split
      backend: cpu

observability:
  http_listen: ":9090"  # Prometheus metrics + health endpoint
  prometheus: true

discovery:
  mdns: true
  mdns_service: "_phonon._tcp"

security:
  tls:
    enabled: false
    cert_file: ""
    key_file: ""
    ca_file: ""
  cors:
    enabled: true
    allowed_origins:
      - "http://localhost:5173"     # Dev UI
      - "http://192.168.1.*"        # LAN access
  body_limits: true
```

---

## 2. Coordinator Smoke Test

### 2.1 Start the Coordinator

```bash
./phonon-coordinator
```

Expected output:
```json
{"time":"...","level":"INFO","msg":"phonon-coordinator starting","version":"0.1.0","phase":"alpha"}
{"time":"...","level":"INFO","msg":"coordinator listening","addr":":9876"}
```

### 2.2 Check Start — Config Validated at Boot

If the config file is corrupt or missing required fields, the coordinator
**exits immediately** with a fatal error:

```bash
# Test with a bad config
PHONON_CONFIG=/nonexistent/phonon.yaml ./phonon-coordinator
# Expected: non-zero exit, log includes "config: open /nonexistent/phonon.yaml: no such file or directory"
```

If a present-but-invalid field exists (e.g. `pool_size: -1`), the validator
logs every violation at WARN level and exits:

```json
{"time":"...","level":"WARN","msg":"config: validation failed","errors":["groups[0].pool_size: must be positive"]}
```

### 2.3 Health Check

```bash
curl -s http://localhost:9876/api/health | jq .
```

Expected:
```json
{
  "status": "ok",
  "groups": 2,
  "paired_devices": []
}
```

### 2.4 Metrics Endpoint

```bash
curl -s http://localhost:9090/metrics | grep phonon
```

Expected: Prometheus metrics prefixed with `phonon_`.

---

## 3. Pairing a Phone (Secure Mode)

> Note: Insecure mode (`cluster.pairing.mode: insecure`) skips pairing.
> Skip to section 4 if using insecure mode.

### 3.1 Get Pairing Code

```bash
curl -X POST http://localhost:9876/api/v1/pair/generate \
  -H "Authorization: Bearer <operator-token>" | jq .
```

Expected:
```json
{
  "code": "ABCD-1234-EFGH-5678",
  "expires_in": 300
}
```

### 3.2 Phone Enters Code

On the phone (sidecar app), enter the pairing code.
The sidecar sends the code to `POST /api/v1/pair/register`.

### 3.3 Verify Pairing

```bash
curl -s http://localhost:9876/api/v1/devices | jq .
```

Expected: Device appears with `status: "paired"`.

---

## 4. Declarative Model Config

### 4.1 Verify Group Reconciliation

After starting with the sample config, the reconciler loop checks each group:

```bash
curl -s http://localhost:9876/api/v1/models | jq .
```

Expected output (truncated):
```json
{
  "models": [
    {"name": "gemma-2-2b-it", "groups": ["small-models"], "status": "waiting"},
    {"name": "llama-3.2-3b-it", "groups": ["large-models"], "status": "waiting"}
  ]
}
```

### 4.2 Config Change → Auto Reconciliation

Edit `phonon.yaml` and change `pool_size: 2` to `pool_size: 3` for
`small-models`. Then trigger a config reload:

```bash
kill -HUP $(pgrep phonon-coordinator)
# Or restart the coordinator
```

### 4.3 Cold Cache Download (A-03)

If a model's `download_url` is set but the model is not yet in the cache,
startup reconciliation triggers a download:

```bash
# Check progress via logs
grep "downloading\|model_cache\|reconcile" /var/log/phonon.log
```

Expected log output (timeouts vary):
```json
{"time":"...","level":"INFO","msg":"model not in cache, downloading","group":"large-models","model":"llama-3.2-3b-it","url":"https://..."}
{"time":"...","level":"INFO","msg":"model cached","group":"large-models","model":"llama-3.2-3b-it","size_bytes":..."
```

After download completes, the reconciler proceeds with model push to
available devices.

---

## 5. Model Push (Coordinator → Sidecar)

### 5.1 Verify Push Command

When a phone is paired and a model needs to be loaded, the coordinator
sends a `model_push` command over the WebSocket:

```
Command: model_push
Payload:
  model: "llama-3.2-3b-it"
  url: "https://..."
  checksum: "a1b2c3d4..."
  size_bytes: 2147483648
```

### 5.2 Verify Sidecar Download

On the phone, check logcat for download progress:

```bash
adb logcat -s PhononCoordinator:D
```

Expected output:
```
PhononCoordinator: Received model_push: llama-3.2-3b-it
PhononCoordinator: Downloading model to /data/data/com.chezgoulet.phonon/cache/models/...
PhononCoordinator: SHA-256 verified
PhononCoordinator: Sending ack: completed
```

### 5.3 Verify Cache

The downloaded model is stored at:
```
/data/data/com.chezgoulet.phonon/cache/models/llama-3.2-3b-it
```

```bash
adb shell ls -la /data/data/com.chezgoulet.phonon/cache/models/
```

---

## 6. Model Load + Checksum Verification

### 6.1 Verify Load Command

After the model is cached, the coordinator sends `model_load`:

```
Command: model_load
Payload:
  model: "llama-3.2-3b-it"
  url: "https://..."
  engine: "litert"
  backend: "auto"
  checksum: "a1b2c3d4..."   # optional, present when group config includes checksum
```

### 6.2 Verify Sidecar Load

```
PhononCoordinator: Loading model: llama-3.2-3b-it (LiteRT-LM, backend=auto)
PhononCoordinator: Model cached: /data/.../llama-3.2-3b-it (2147483648 bytes)
PhononCoordinator: Verifying checksum for llama-3.2-3b-it
PhononCoordinator: Checksum verified for llama-3.2-3b-it
```

### 6.3 Checksum Mismatch (Negative Test)

Delete the cached file and replace it with garbage:

```bash
adb shell "echo 'corrupted' > /data/data/com.chezgoulet.phonon/cache/models/llama-3.2-3b-it"
```

Then trigger a model reload (restart sidecar or use coordinator UI).

Expected logcat output:
```
PhononCoordinator: Checksum mismatch for llama-3.2-3b-it: expected a1b2c3d4..., got deadbeef... — deleting and forcing re-download
```

The corrupted file is deleted, a new `model_push` is issued, and the model
is re-downloaded before loading.

### 6.4 Model Unload

```
Command: model_unload
```

Expected:
```
PhononCoordinator: Model unloaded: llama-3.2-3b-it
```

---

## 7. OpenAI-Compatible Inference

### 7.1 Chat Completion

```bash
curl -s http://localhost:9876/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-2-2b-it",
    "messages": [{"role": "user", "content": "Hello, how are you?"}]
  }' | jq .
```

Expected:
```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1718000000,
  "model": "gemma-2-2b-it",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "I'm doing well, thank you!"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 20,
    "total_tokens": 30
  }
}
```

### 7.2 Streaming

```bash
curl -s -N http://localhost:9876/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-2-2b-it",
    "stream": true,
    "messages": [{"role": "user", "content": "Count to 5"}]
  }'
```

Expected: SSE events (`data: {"choices":[...]}`) followed by `data: [DONE]`.

### 7.3 Model Listing

```bash
curl -s http://localhost:9876/v1/models | jq .
```

Expected:
```json
{
  "object": "list",
  "data": [
    {"id": "gemma-2-2b-it", "object": "model", "created": ..., "owned_by": "phonon"},
    {"id": "llama-3.2-3b-it", "object": "model", "created": ..., "owned_by": "phonon"}
  ]
}
```

---

## 8. Health & Metrics

### 8.1 Device Health

```bash
curl -s http://localhost:9876/api/v1/devices | jq .
```

Expected:
```json
{
  "devices": [
    {
      "id": "PHONE-001",
      "model": "Pixel 7a",
      "status": "online",
      "ip": "192.168.1.101",
      "last_seen": "...",
      "load": 0.5,
      "temperature": 38.2
    }
  ]
}
```

### 8.2 Group Status

```bash
curl -s http://localhost:9876/api/v1/groups | jq .
```

Expected: Each group shows `desired_pool`, `current_pool`, `backends`.

### 8.3 Event Log

```bash
curl -s "http://localhost:9876/api/v1/events?limit=10" | jq .
```

Expected: Recent events (pair, unpair, push, load, unload, health changes).

---

## 9. Failover & Recovery

### 9.1 Device Disconnect

Unplug a phone's Ethernet cable.

Expected:
- Coordinator logs: `device PHONE-001 disconnected`
- Device status changes to `offline`
- Pool rebalances: another device in the group picks up the load
- Prometheus: `phonon_device_status{device="PHONE-001"} = 0`

### 9.2 Device Reconnect

Plug the Ethernet cable back in.

Expected:
- Coordinator rediscovers the device
- If the device was in a group, the coordinator re-sends pending commands
  (model_push + model_load)
- Device status returns to `online`

### 9.3 Coordinator Restart

```bash
kill $(pgrep phonon-coordinator)
./phonon-coordinator
```

Expected:
- On restart, the coordinator reconnects to paired devices
- Devices that were previously loaded are reloaded
- No duplicate model pushes (coordinator checks cache before pushing)
- Pairing state persists in the configured store (FileStore or Redis)

---

## 10. Security: TLS, CORS, Body Limits

### 10.1 Request Body Size Limits (A-06)

```bash
# Chat completion with oversized body (over 1 MB)
dd if=/dev/zero bs=1024 count=1500 | base64 | \
curl -s -X POST http://localhost:9876/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d @- | jq .
```

Expected: HTTP 400 with body size exceeded error.

```bash
# Sidecar registration within 64 KB limit — normal payload
curl -s -X POST http://localhost:9876/api/v1/sidecar/register \
  -H "Content-Type: application/json" \
  -d '{"device_id": "TEST-001", "model": "pixel-test", "ip_address": "192.168.1.1"}' | jq .
```

Expected: HTTP 200 (normal operation unaffected).

### 10.2 Unpair from Body, Not Query Param (A-07)

```bash
# Correct: device_id in JSON body
curl -s -X POST http://localhost:9876/api/v1/pair/unpair \
  -H "Authorization: Bearer <operator-token>" \
  -H "Content-Type: application/json" \
  -d '{"device_id": "PHONE-001"}' | jq .
```

Expected: HTTP 200, device unpaired.

```bash
# Old query-param style should NOT work
curl -s "http://localhost:9876/api/v1/pair/unpair?device_id=PHONE-001" \
  -H "Authorization: Bearer <operator-token>" | jq .
```

Expected: HTTP 400 or the body is read and device_id from query is ignored.

### 10.3 WebSocket Limits (A-08)

The sidecar WebSocket has:
- **Read limit:** 64 KB per message (payloads > 64 KB are rejected)
- **Pong wait:** 30 seconds (connection dropped if no pong received)
- **Write deadline:** 10 seconds (slow consumers are timed out)

Normal sidecar operations produce payloads under 1 KB, so this should not
affect normal operation.

### 10.4 Register IP from RemoteAddr (A-09)

```bash
# Register with a spoofed IP in the body — the coordinator should derive
# IP from RemoteAddr, not from the body field.
curl -s -X POST http://localhost:9876/api/v1/sidecar/register \
  -H "Content-Type: application/json" \
  -d '{"device_id": "TEST-002", "model": "pixel-test", "ip_address": "10.0.0.1"}' | jq .
```

Expected: Device appears in `GET /api/v1/devices` with the coordinator's
own remote address (not `10.0.0.1`).

### 10.5 TLS (A-05)

When `security.tls.enabled` is true:

```bash
# Generate self-signed cert
openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem \
  -days 365 -nodes -subj "/CN=phonon.local"

# Start coordinator with TLS
PHONON_TLS_CERT=cert.pem PHONON_TLS_KEY=key.pem ./phonon-coordinator

# Verify (skip-verify for self-signed cert)
curl -sk https://localhost:9876/api/health | jq .
```

Expected: Health check works over HTTPS.

### 10.6 CORS with Allowlist (A-10)

```bash
# Request from an allowed origin
curl -s -H "Origin: http://localhost:5173" \
  -H "Access-Control-Request-Method: GET" \
  -X OPTIONS http://localhost:9876/api/v1/devices -v 2>&1 | grep -i "access-control"

# Expected: Access-Control-Allow-Origin: http://localhost:5173

# Request from a disallowed origin
curl -s -H "Origin: https://evil.com" \
  -H "Access-Control-Request-Method: GET" \
  -X OPTIONS http://localhost:9876/api/v1/devices -v 2>&1 | grep -i "access-control"

# Expected: NO Access-Control-Allow-Origin header (requests blocked)
```

---

## 11. Full Sprint A Checklist

Use this checklist to verify Sprint A (v0.0.2) is complete.

### Core

- [ ] Coordinator starts and validates config at boot
- [ ] Config validation rejects invalid configs with clear error messages
- [ ] Reconciliation loop picks up groups from config
- [ ] Cold cache download works when model not in cache (`download_url` set)

### Model Pipeline

- [ ] `model_push` sent to sidecar over WebSocket with URL + checksum
- [ ] Sidecar downloads model on `model_push` receipt
- [ ] Sidecar verifies SHA-256 checksum after download
- [ ] `model_load` sent with optional checksum field
- [ ] Sidecar verifies checksum before loading into LiteRT-LM engine
- [ ] Checksum mismatch on existing file → delete + re-download

### API

- [ ] OpenAI-compatible `/v1/chat/completions` works (non-streaming + streaming)
- [ ] `/v1/models` returns configured models
- [ ] Request body size limits enforced per route
- [ ] Unpair reads `device_id` from JSON body, not query param

### Security

- [ ] WebSocket read limit (64 KB) + pong wait (30s) + write deadline (10s)
- [ ] Register handler uses `RemoteAddr`, ignores body IP
- [ ] TLS works with self-signed cert (A-05)
- [ ] CORS allowlist rejects unauthorized origins (A-10)
- [ ] Operator auth required for administrative routes

### Observability

- [ ] Prometheus metrics available
- [ ] Health endpoint returns cluster status
- [ ] Event log shows recent state changes

### Documentation

- [ ] This test document — curl commands for every feature
- [ ] Test results logged following these procedures

---

## Appendix: Testing on a Coordinator Without Phones

If you don't have physical Android phones, you can still verify most coordinator
functionality by connecting via a WebSocket client:

```bash
# Install websocat or use python
pip install websocat

# Start coordinator, then connect a fake sidecar
websocat "ws://localhost:9876/ws?device_id=FAKE-001&model=fake"
> {"type": "register", "payload": {"device_id": "FAKE-001", "model": "Pixel 7a"}}
```

You'll see any pending commands (model_push, model_load) sent to the connected
client. Pairing can be tested using the insecure mode without device auth.

---

*End of test document.*
*Last updated: 2026-06-16*
