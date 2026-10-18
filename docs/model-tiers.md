# Model tiers on an 8 GB phone

Measured 2026-10-17 on the stock Pixel 7 (panther, Android 17, 7.65 GB visible),
through the app, same prompt, same settings (`-c 2048 -t 4 -np 1 -fa on
--jinja --reasoning off`), phone at 24 °C, model resident.

| Model | File size | Prefill | Decode | 128-token wall | Tool call |
|---|---|---|---|---|---|
| Qwen3.5-2B Q4_K_M | 1.28 GB | 11.46 tok/s | 5.98 tok/s | 24.6 s | yes — `get_weather({"city":"Montreal"})` |
| Gemma 4 E2B Q4_0 | 2.84 GB | 8.36 tok/s | 5.13 tok/s | 27.3 s (stopped at 119 tokens) | yes |

Both models returned `finish_reason: tool_calls` with the correct function name
and arguments on the same request.

## What this says

- Under normal memory conditions the two land in the same band: **5–6 tok/s
  decode**. The gap between them is smaller than the run-to-run variance.
- Qwen3.5-2B is half the storage, slightly faster at both prefill and decode,
  and leaves far more headroom on an 8 GB device. For the 8 GB tier it is the
  better default.
- Gemma 4 E2B's earlier 10.04 tok/s decode was measured immediately after
  `am kill-all` freed memory (4.3 GB available). It is not a representative
  server number, and it should not be quoted as one.
- An earlier E2B run reported 17.86 tok/s prefill on a 34-token prompt; the
  same model measured 8.36 on a longer prompt under normal memory. Short
  prompts and freed memory both flatter the number.
- Model choice on 8 GB should therefore be made on quality and tool-call
  reliability, not speed. Speed is set by the memory bus and the governor,
  and both models sit against the same wall.


## Tool-call reliability

Five requests per model, same tool, same prompt, temperature 0.7, through the
app:

| Model | Valid tool calls |
|---|---|
| Qwen3.5-2B | 5/5 |
| Gemma 4 E2B | 5/5 |

Both models produced `get_weather` with `{"city":"Montreal"}` on every trial.
This is a small sample and says nothing about multi-step loops; it does say
that single-call tool use is not the weak point at this size.

## Known issue found during this comparison

After changing `model_path` via `POST /settings`, `/health` can report
`state: "ready"` with `pid: null` for a few seconds while the old server is
gone and the new one has not registered. During that window `/v1/models`
returns `[]` and `/v1` returns 503. The state machine should report
`loading` (or `restarting`) until the child process exists. Not fixed yet.

## Reproduce

```bash
# stage a model and serve it to the phone over the USB cable
python3 -m http.server 9002 --directory $HOME/models &
adb -s <serial> reverse tcp:9002 tcp:9002
curl -X POST http://127.0.0.1:8080/model/download \
  -d '{"url":"http://127.0.0.1:9002/Qwen3.5-2B-Q4_K_M.gguf"}'

# switch to it
curl -X POST http://127.0.0.1:8080/settings \
  -d '{"model_path":"/data/user/0/org.phononai.app/files/models/Qwen3.5-2B-Q4_K_M.gguf","alias":"qwen3.5-2b"}'
```

## Context window on 8 GB

Measured with Qwen3.5-2B Q4_K_M, standalone `llama-server`, same phone,
`-fa on -t 4 -np 1`:

| Context | RSS | Decode | Prefill |
|---|---|---|---|
| 16,384 | 1.57 GB | 13.85 tok/s | 22.5 tok/s |
| 32,768 | 1.76 GB | 13.90 tok/s | 21.9 tok/s |

A 4,339-token prompt at 32K prefilled at 20.7 tok/s and completed correctly in
209.6 s. Memory cost is about **12 MB per 1,000 tokens** of context, because
Qwen3.5's hybrid architecture keeps a constant-size state in 24 of its 32 layers
and only the 8 full-attention layers grow a KV cache. Doubling 16K to 32K cost
190 MB and nothing measurable in speed.

Gemma 4 E2B uses ordinary attention throughout; its KV grows with every layer
and is far more expensive at the same context. For long agent sessions on 8 GB,
Qwen3.5 is the right model.

## The cpuset penalty — bigger than context

The app's server runs about **2.4x slower than the same binary started from a
shell**: 5.8 tok/s in-app against 13.9 standalone, same model, same context.

Cause, measured:

```
app child:  cpuset:/foreground   Cpus_allowed_list: 0-5
/dev/cpuset/top-app/cpus:    0-7
/dev/cpuset/foreground/cpus: 0-5
/dev/cpuset/background/cpus: 0-3
```

On a Pixel 7 the cores are 0–3 little, 4–5 mid, 6–7 Cortex-X1. Android puts a
foreground *service* in `/foreground`, which excludes the two big cores. The
standalone server, started from `adb shell`, is not in that cpuset and gets all
eight.

An app cannot change its own cpuset. The fix belongs to the appliance image or
ROM: put the service in `top-app`, or widen `/foreground` to include 6–7. Until
then, treat the in-app number as the honest product number and the standalone
number as the hardware ceiling.

### Confirmed: top-app versus foreground (two phones, same everything)

The cpuset hypothesis is now proven by comparing two Pixel 7s running the same
model, context and binary:

| State | cpuset | Allowed CPUs | Decode |
|---|---|---|---|
| screen locked, foreground service | `/foreground` | 0–5 | 5.8 tok/s |
| screen on, Activity visible | `/top-app` | 0–7 | 14.36 tok/s |

Keeping the screen awake while charging (`svc power stayon usb`) holds the
process in `top-app`: 12.7–14.4 tok/s across runs.

So the 2.4x penalty is not a property of the app or the model; it is which
cgroup Android assigns. An appliance must either keep the display awake (cheap
in a rack, and the display can be dimmed) or, in the ROM, move the service to
`top-app` or widen `/foreground`.
