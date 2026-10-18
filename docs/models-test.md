# Model-family acceptance run (2026-10-17)

Device: stock Pixel 7 (`panther`), serial `<device-serial>`, Android 17,
7.83 GB reported RAM, tier S. App 0.4.0 debug, engine `797de63` with
`GGML_CPU_REPACK=ON`. No reboot, no data clear. Phone on USB power, screen on,
activity visible (`top-app`), 2 threads on cores 6-7.

Installed build:
`app/app/build/outputs/apk/debug/app-debug.apk`, installed with
`adb install -r` over the existing 0.4.0 install.

Both files were already on the phone under `/data/local/tmp/models`. They were
copied into the app's storage, not downloaded:

| File | Bytes | MD5 on device |
|---|---:|---|
| `Qwen3.5-2B-Q4_K_M.gguf` | 1,280,835,840 | `6899b2510c7b0c480730d48c3117bd32` |
| `gemma-4-E2B-it-Q4_0.gguf` | 2,841,481,184 | `65c6de37e4e5d9f5372abf42f268cd36` |

The Qwen MD5 matches the file already used for the 9.20 / 8.84 tok/s
measurement. The Gemma MD5 matches `/data/local/tmp/models/gemma-4-E2B-it-Q4_0.gguf`.
No model file was renamed, replaced or deleted; the temporary Setup test below
moved the names and restored them in the same command.

## Switching to Gemma 4 from the picker

Tapping **Use** on Gemma 4 E2B:

- `GET /settings` then reported
  `model_path=…/gemma-4-E2B-it-Q4_0.gguf`, `context=16384`,
  `alias=gemma-4-e2b`;
- `/health` went `loading` → `ready` with the Gemma file and a new pid;
- `GET /v1/models` returned one model with `id` and `aliases` both
  `gemma-4-e2b`.

So the alias moved with the family and the server restarted.

## Decode on Gemma 4 (through the app proxy)

Same prompt and shape as the Qwen rows: 128 tokens, `temperature: 0`,
`ignore_eos: true`, non-streaming, `POST /v1/chat/completions` on port 8080.
Read from `timings.predicted_per_second`, not wall clock.

| Run | Tokens | Decode |
|---|---:|---:|
| 1 | 128 | **8.27 tok/s** |
| 2 | 128 | **7.55 tok/s** |

Reference raw-server numbers for the same file are 7.05 / 6.74 tok/s
(`docs/performance.md`). The app-proxy runs here are slightly faster; the
catalog keeps the raw-server figures and the date, not these.

## Tool call sanity — Gemma 4

Request: one `get_weather` tool with `city` required, prompt
"What is the weather in Paris right now? Use the get_weather tool.",
`tool_choice: auto`, `temperature: 0`.

Observed response:

```json
{
  "finish_reason": "tool_calls",
  "tool_calls": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "arguments": "{\"city\":\"Paris\"}"
      },
      "id": "3kByz5aCNim0qrz5fBB90htFkqtCsq9E"
    }
  ]
}
```

Name and arguments are correct; Gemma's own tool format is handled by the
`--jinja` chat template.

## Switching back to Qwen3.5

Tapping **Use** on Qwen3.5-2B:

- `GET /settings`: `model_path=…/Qwen3.5-2B-Q4_K_M.gguf`, `context=32768`,
  `alias=qwen3.5-2b`;
- `/health`: `loading` → `ready`, new pid, no error;
- `/v1/models`: `id=aliases=qwen3.5-2b`.

Decode through the same proxy:

| Run | Tokens | Decode | Note |
|---|---:|---:|---|
| 1 | 128 | 7.66 tok/s | first after the switch; Qwen pages were cold |
| 2 | 128 | 7.47 tok/s | immediately after run 1 |
| 3 | 128 | **9.03 tok/s** | after 60 s idle |
| 4 | 128 | 8.06 tok/s | after another 60 s idle |
| 5 | 128 | **9.58 tok/s** | after another 60 s idle |

The two 128-token reference runs were 9.20 / 8.84 (raw server) and 8.59 / 8.25
(through the app). The first Qwen request after Gemma had evicted its clean
pages from the page cache decodes at 7.5–7.7; warm runs reach 9.0–9.6. The
earlier performance protocol discarded a warm-up for the same reason. Qwen
therefore still reaches ~9 tok/s after switching back, but a cold first
completion does not, and this run reports both instead of averaging them away.

No Qwen number in the catalog or docs was changed by this run.

## Tool call sanity — Qwen3.5

Same request with `model: qwen3.5-2b`:

```json
{
  "finish_reason": "tool_calls",
  "tool_calls": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "arguments": "{\"city\":\"Paris\"}"
      },
      "id": "YeIZ8jqfBzXBrtPUCZZlsN5xIciy6Osu"
    }
  ]
}
```

## Setup screen

The Setup screen shows both families with **Qwen3.5 pre-selected** and one
sentence of trade-off: Qwen3.5 about 9.2 tok/s, Gemma 4 about 7.0 tok/s with a
multimodal path and its own tool format. To render it without clearing app
data, the two model files were temporarily renamed (`.hold-*`) so the models
list was empty, a screenshot was taken, and both names were restored in the
same command. `/health` stayed `ready` and the directory listing afterwards
showed both original names.

## Regression checks

| Check | Result |
|---|---|
| `GET /health` | 200, `"state":"ready"`, model `Qwen3.5-2B-Q4_K_M.gguf` |
| `GET /settings` without key | 401 |
| `GET /settings` with key | 200 |
| `GET /v1/models` without key | 401 |
| `GET /v1/models` with key | 200, one alias |
| `POST /settings {"bind_address":"0.0.0.0","api_key":""}` | 400, `{"error":"bind_address requires api_key"}` |
| settings after the refusal | 200; `api_key_set=true`, bind `0.0.0.0`, alias `qwen3.5-2b` |
| completion through `/v1/chat/completions` | 200, 128 tokens, non-empty content, both families |

The API key was not changed and is not printed here.

## Screenshots

- `design/renders/app-models-families-qwen.png` — Models picker, Qwen3.5-2B
  active, both families and the unmeasured marker visible.
- `design/renders/app-models-families-gemma.png` — the same picker after the
  switch, Gemma 4 E2B highlighted, alias `gemma-4-e2b`.
- `design/renders/app-setup-families.png` — Setup with the family choice,
  Qwen pre-selected.

## Reproduce

```bash
KEY=$(cat /tmp/phone_a_key)
# decode
curl -sS -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"gemma-4-e2b","messages":[{"role":"user","content":"<fixed prompt>"}],
       "max_tokens":128,"temperature":0,"ignore_eos":true,"stream":false}' \
  http://127.0.0.1:18080/v1/chat/completions
# tool call: post a get_weather schema and read finish_reason/tool_calls
```

Switching is a picker action, not an HTTP route: it writes the catalog entry's
file, context and alias and reloads settings, which restarts `llama-server`.
