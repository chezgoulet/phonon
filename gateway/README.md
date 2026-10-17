# Gateway (LiteLLM)

The gateway is configuration, not code. It gives one OpenAI-compatible URL in
front of one or more phones, with per-phone routing, timeouts and metrics.

## Bench setup (verified 2026-10-17)

The test phone is connected by USB to sasquatch, so it is reachable on the host
at `127.0.0.1:8080` through `adb forward tcp:8080 tcp:8080`. The container runs
with `--network host` so that address is the same inside it.

```bash
adb -s <serial> forward tcp:8080 tcp:8080
KEY=$(openssl rand -hex 32); printf '%s' "$KEY" > ~/.litellm-key; chmod 600 ~/.litellm-key

docker run -d --name phonon-litellm --network host \
  -e LITELLM_MASTER_KEY="$KEY" \
  -v "$PWD/gateway/litellm-bench.yaml:/app/config.yaml:ro" \
  ghcr.io/berriai/litellm:main-latest \
  --config /app/config.yaml --port 4000 --host 0.0.0.0
```

The master key lives in `~/.litellm-key`, never in the repository.

## Verified

- `GET /v1/models` → `phone-a`
- A completion routed to the phone: `"Hello! How can I help you today"`
- A tool call routed through the gateway:
  `finish_reason: tool_calls`, `get_weather({"city":"Montreal"})`
- A 2,298-token prompt through the gateway: **145.5 s**, prefill 11.80 tok/s,
  correct short answer. The gateway and the app's proxy both survive requests
  that take minutes.

## What this costs

At ~11.8 tok/s prefill on an 8 GB phone, prompt length is the real currency:

| Prompt | Prefill time |
|---|---|
| 1,100 tokens | ~93 s |
| 2,300 tokens | ~145 s |
| 3,000 tokens (opencode's trimmed agent) | ~4 min |

So an agent turn is minutes, not seconds. That is workable for a serial
overnight lane and painful for interactive use. Shrinking the system prompt and
tool schemas is worth more than any decoding optimisation.

## Not working yet: opencode through the gateway

Pointing opencode 2 at `litellm/phone-a` fails in two ways:

1. With the default agent and 8K declared context: `The compaction request
   cannot be reduced further...` — opencode's own prompt accounting trips
   before the request is sent.
2. With the trimmed `phone-coder` agent: `The socket connection was closed
   unexpectedly`.

The gateway itself is not at fault: the same 2.3K-token request succeeds with
curl in 145 s. The next step is to find which client-side timeout closes the
socket, and to shrink the prompt further (a two-tool agent is the target).

## Production shape

In a real deployment, each phone is a deployment with a pinned alias
(`phone-a`, `phone-b`) so an agent's turns stay on one phone and its KV prefix
cache survives; the pooled name is for one-shot calls. See
[`litellm.yaml`](litellm.yaml) for the two-phone template and the notes on the
router's cloud-shaped defaults.
