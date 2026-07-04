# PHONE-API — Coordinator ↔ Sidecar Wire Protocol

This document specifies the wire protocol between the **Phonon coordinator**
(Go, `cmd/phonon-coordinator`) and the **Android sidecar** (Kotlin,
`sidecar/`). It is the single reference for how the coordinator dispatches
inference to a phone and how it probes phone health. Until now the protocol was
implicit across three files in two languages:

- Coordinator request construction — [`internal/api/openai.go`](../internal/api/openai.go)
  (`phoneInferenceURL`, `PhoneInferenceRequest`, `PhoneInferenceResponse`)
- Sidecar request handling — [`InferenceServer.kt`](../sidecar/app/src/main/kotlin/com/chezgoulet/phonon/inference/InferenceServer.kt)
- Coordinator health probe — [`internal/health/monitor.go`](../internal/health/monitor.go)

> **Status:** ALPHA. The sidecar's LiteRT-LM engine is synchronous, so streaming
> is *approximated* (full generation, then chunked delta emission). See
> [Streaming](#streaming-sse) below.

---

## Transport

- **Protocol:** plain HTTP/1.1 over the LAN. No TLS between coordinator and
  phone (both are on a trusted subnet; the coordinator's *client-facing* API can
  be TLS-terminated separately).
- **Target URL:** `http://<phone-ip>:<inference_port>` where `inference_port`
  defaults to **9876**.
  - Coordinator side: `cluster.inference_port` in `phonon.yaml`
    (`internal/config/types.go`, field `InferencePort`).
  - Sidecar side: `PHONON_INFERENCE_PORT` environment variable.
  - **These must match.** See [Port configuration](#port-configuration).
- The coordinator builds the URL via `phoneInferenceURL(ip)` →
  `http://<ip>:<port>/v1/chat/completions`.

## Authentication

- Every inference request carries `Authorization: Bearer <device-token>`.
- The `<device-token>` is the per-device secret established during **pairing**
  (Ed25519 key exchange). The coordinator looks it up via the
  `WithDeviceTokenLookup` option; it is **never** placed in the JSON body
  (`PhoneInferenceRequest.AuthToken` has struct tag `json:"-"`).
- The coordinator also sets `X-Phonon-Proxy: coordinator` on inference requests.
- Sidecar `authorize()` rules (fail-closed, constant-time compare):
  - Not yet paired (no token) → **403**.
  - Paired but missing/invalid bearer token → **401**.
- The `GET /health` probe is **not** authenticated.

---

## Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/v1/chat/completions` | Bearer | Inference (streaming + non-streaming). Coordinator's default target. |
| `POST` | `/infer` | Bearer | Alias of the above — same handler on the sidecar. |
| `GET`  | `/health` | none | Liveness + model status probe. |

Any other path/method → **404** `{"error":"Not found"}`.

---

## Inference request

Body sent by the coordinator (`PhoneInferenceRequest`, marshaled to JSON):

```json
{
  "model": "gemma-2b-it-q4",
  "messages": [
    {"role": "system", "content": "You are helpful."},
    {"role": "user", "content": "Hello"}
  ],
  "temperature": 0.7,
  "max_tokens": 2048,
  "stream": false,
  "timeout_ms": 30000,
  "trace_id": "abc123"
}
```

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `model` | string | yes | Model identifier the phone should have loaded. |
| `messages` | array | yes | `{role, content}` objects; role ∈ `system\|user\|assistant`. |
| `temperature` | number | yes | Sampling temperature. Coordinator defaults to `0.7`. |
| `max_tokens` | int | yes | Coordinator defaults to `2048`; also drives `timeout_ms`. |
| `stream` | bool | no | `true` → SSE. Omitted/`false` → single JSON response. |
| `timeout_ms` | int | no | Per-request deadline (derived from `max_tokens`). Informational for the phone; the coordinator enforces it client-side. |
| `trace_id` | string | no | Correlates with the coordinator-side request trace. |

The bearer token is transported as a header, not a body field.

---

## Non-streaming response

The sidecar returns HTTP **200** with an **OpenAI-compatible `chat.completion`**
object:

```json
{
  "id": "chatcmpl-1720102030",
  "object": "chat.completion",
  "created": 1720102030,
  "model": "gemma-2b-it-q4",
  "choices": [
    {"index": 0,
     "message": {"role": "assistant", "content": "Hello there!"},
     "finish_reason": "stop"}
  ],
  "usage": {"prompt_tokens": 8, "completion_tokens": 3, "total_tokens": 11}
}
```

Token counts are rough estimates (`chars / 4`).

The **OpenAI `chat.completion` shape is canonical** for what the sidecar sends.
The coordinator's `defaultInferenceProxy` reads `choices[0].message.content` and
`usage.completion_tokens` from it (and still accepts a flat `{text, tokens,
duration_ms}` body for back-compat). This is exercised end to end by
`TestCoordinatorSidecarContract` in `internal/api/contract_test.go`, which runs
the real proxy against a sidecar that speaks this protocol.

---

## Streaming (SSE)

When `stream: true`, the sidecar responds with `Content-Type:
text/event-stream` over **chunked transfer encoding**, emitting OpenAI
`chat.completion.chunk` deltas:

```
data: {"choices":[{"delta":{"content":"Hello"},"index":0}]}

data: {"choices":[{"delta":{"content":" world"},"index":0}]}

data: [DONE]
```

- **Keepalive:** because generation is synchronous, the sidecar commits the SSE
  headers immediately and emits an SSE **comment** line `: keepalive\n\n` every
  **2 seconds** while the model runs. Comment lines (leading `:`) are ignored by
  SSE parsers but keep the byte stream alive so the coordinator's per-chunk
  stall detector does not fire. **This 2 s interval is a protocol contract:** any
  coordinator-side stall timeout must be greater than `2 s + expected inter-token
  latency`. See issue #277 and the constant in `InferenceServer.kt`.
- **Delta chunking:** generated text is split on word boundaries and emitted at
  ~10 ms intervals. Non-whitespace-separated scripts (CJK) fall back to
  fixed-size character chunks (see issue #276).
- **Terminator:** the stream ends with `data: [DONE]` then the chunked stream is
  closed.

### Streaming errors (in-band)

Once SSE headers are sent, errors are reported in-band (the HTTP status is
already 200):

```
data: {"error":{"message":"No model loaded","type":"inference_error"}}

data: [DONE]
```

---

## Error format (non-streaming)

Before headers are committed, the sidecar uses HTTP status codes with a JSON
body:

| Status | Condition | Body |
|--------|-----------|------|
| 401 | Missing/invalid bearer token | `{"error":"unauthorized","message":"..."}` |
| 403 | Phone not yet paired | (unauthorized) |
| 404 | Unknown path/method | `{"error":"Not found"}` |
| 502 | No model loaded / inference failed | `{"error":"No model loaded","code":"no_model"}` |
| 504 | Inference timed out | `{"error":"Inference timed out"}` |

The coordinator treats any non-200 as a phone failure (surfaces `phone returned
HTTP <n>: <body>` and, for repeated failures, trips the per-device circuit
breaker).

---

## Health probe

`GET /health` (unauthenticated), probed by the coordinator's health monitor
every check cycle at `http://<phone-ip>:<inference_port>/health`. Proves the
inference server itself is serving — not merely that `PhononService`
heartbeats.

Response — HTTP **200**:

```json
{
  "status": "ok",
  "model_loaded": true,
  "model": "gemma-2b-it-q4",
  "backend": "litert-lm"
}
```

| Field | Type | Notes |
|-------|------|-------|
| `status` | string | `"ok"` when serving. |
| `model_loaded` | bool | Whether an engine/model is currently loaded. |
| `model` | string \| null | Loaded model name, or `null`. |
| `backend` | string \| null | Inference backend (e.g. `litert-lm`), or `null`. |

Fields the coordinator's richer telemetry expects but that a minimal phone may
omit (battery, thermal, queue depth, viz-pack state) are gracefully defaulted.

---

## Port configuration

The coordinator and sidecar **both** default `inference_port` to **9876**. If an
operator changes `cluster.inference_port` on the coordinator but forgets
`PHONON_INFERENCE_PORT` on the sidecars (or vice versa), the coordinator probes
and dispatches inference to the wrong port.

**Symptom:** every phone appears *unreachable for inference* — heartbeats over
the WebSocket command channel still succeed (that's a different port), but
`/health` probes and inference POSTs all fail.

**Fix:** set the same port on both sides. On startup the coordinator logs the
configured inference port and the sidecar env-var name it must match (see issue
#281):

```
inference port configured  port=9876  sidecar_env=PHONON_INFERENCE_PORT
```

---

## Related

- Circuit breaker / resilience: `internal/api/circuitbreaker.go`, `resilience_test.go`
- Health monitor: `internal/health/monitor.go`
- Config: `internal/config/types.go`, `phonon.example.yaml`
- Design spec: [`PHONON.md`](../PHONON.md), [`SPEC.md`](../SPEC.md)
