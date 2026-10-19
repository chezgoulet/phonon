# Home Assistant, without an integration

Home Assistant's **built-in Ollama integration** can use a Phonon node as its
conversation agent (and, on recent versions, as an AI Task backend). Nothing is
installed on the HA side beyond the integration that ships with core: add it,
paste `http://<phone>:8080`, paste the node key, pick the model.

This document covers what Phonon implements, how to add it, and what is
deliberately missing.

## What Phonon implements

Thin adapters over the existing llama-server backend. There is no second
inference path and no second model.

| Route | Behaviour |
|---|---|
| `GET /api/version` | `{"version": "0.6.8"}` — the surface HA's client expects. |
| `GET /api/tags` | one model: the alias the engine was launched with, its size, its catalog SHA-256 (`sha256:unknown` when the file came from a pasted URL), and GGUF details. |
| `GET /api/ps` | the same model, reported as resident with no expiry (it is). |
| `POST /api/show` | details, `model_info`, and `capabilities: ["completion", "tools"]`. |
| `POST /api/chat` | accepts Ollama's request, calls `/v1/chat/completions`, returns **NDJSON** — one object per chunk, then a final object with `done: true` and the eval counts. |
| `/api/pull` | **refused** with 400 and a sentence saying the weights are already on the device. |

Request translation:

* `model` is ignored — there is one served model, and requiring an exact GGUF
  filename would break setup for every renamed file.
* `messages` pass through; images become `image_url` data URLs, and a `tool`
  message without an id gets one.
* `tools` are already OpenAI-shaped and pass through unchanged.
* `options` maps `temperature`, `top_p`, `top_k`, `num_predict` (→
  `max_tokens`), `repeat_penalty`, `seed` and `stop`.
* `format` maps `"json"` to `json_object`, and an OpenAPI schema (what HA's AI
  Task sends) to a `json_schema` response format.
* `keep_alive` and `options.num_ctx` are **no-ops**: the model never unloads and
  the context is fixed at launch. They are accepted rather than rejected so a
  client that always sends them works.

Response translation:

* One NDJSON line per content delta, flushed as it is produced, so HA's answer
  appears incrementally.
* `reasoning_content` becomes Ollama's `thinking`.
* Tool-call deltas are accumulated by index and emitted as one complete
  `tool_calls` message before the final line — llama-server splits a call
  across many chunks, and Ollama wants one object with an arguments object.
* Token counts come from the usage chunk llama-server sends *after* the finish
  chunk, which is why the final line is written at `[DONE]` rather than at
  `finish_reason`.

## Adding it to Home Assistant

1. Settings → Devices & services → Add integration → **Ollama**.
2. URL: `http://<phone-ip>:8080`. Key: the node's API key (the same one
   `/v1` uses). The phone must be bound off-loopback with a key set — the app
   refuses to expose itself otherwise.
3. The model picker lists the node's current alias, e.g. `qwen3.5-2b:latest`.
4. For tool control, enable "Control Home Assistant" in the conversation
   agent's options. Keep the exposed entity list short (≤25): every entity is
   schema tokens in a prompt on a 2B model.

## Tools on a small model

The tool path works, but a 2B model on a phone has a small context and a
tool schema costs tokens. Measured on the reference phone with a single
`HassTurnOn` tool, the model emits the call reliably; with a full HA entity
exposure list the request can exceed the context and llama-server truncates the
prompt, at which point the model answers in prose instead of calling a tool.
That is a model/context limit, not an API failure, and the fix is to expose
fewer entities or raise the context — not to retry.

## Verifying without a phone install

The `/api/` routes live in `OllamaApi`, which has no Android dependency. The
test sources include a small harness that serves the **same class** on the JVM
so the real `ollama` Python client (the one HA uses) can drive it:

    # a llama-server on a loopback port, any model
    ./app/gradlew -p app :app:ollamaShim &
    python -c 'import ollama; print(ollama.Client(host="http://127.0.0.1:11434").list())'

`tools/record-ha-stream.sh` re-records the SSE fixtures the relay tests replay,
from the same command, when the engine revision moves.

## Not implemented

* **MQTT discovery.** Layer 2 of the issue: retained discovery configs for
  state, model, tokens/s, queue depth, battery temperature, SoC, hours above
  80%, serving/thermally-paused binary sensors, and start/stop/test buttons,
  with the broker password in the same encrypted store as the gateway key.
  This PR is Layer 1 only. The surface above is what HA's Ollama integration
  needs; MQTT is additive and can land separately.
* **`/api/pull`.** Refused on purpose.
* **A HACS integration.** The issue defers it, and so does this.
