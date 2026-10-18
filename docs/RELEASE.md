# Release notes — v0.1.0

Draft for the first public release. Do not publish until the tag is cut; the
figures below are measured and should be re-read against `docs/performance.md`
before they are pasted anywhere permanent.

## What it is

Phonon turns an Android phone into an OpenAI-compatible inference node on your
own network. The phone owns the model and the server; a gateway on any machine
routes to one or more phones; an agent runs on a host and cannot tell the
difference from any other OpenAI endpoint.

## What this build does

- Packages `llama-server` and serves `/v1/chat/completions`, `/v1/models` and a
  `/health` endpoint on the phone, behind an API key.
- Downloads GGUF models into app storage with a size check, and offers five
  families: Qwen3.5, Gemma 4, MiniCPM5, Granite and Nemotron.
- Returns structured OpenAI `tool_calls` for every shipped family (verified on
  device with a `get_weather` call).
- Supervises the server from a foreground service, auto-starts at boot, pauses
  on temperature and resumes when it cools.
- Serves a kiosk mode through Device Owner where the device allows it, and says
  plainly when it does not.
- Exports a device report so a failing node can be debugged without guesswork.

## Measured on a Pixel 7 (8 GB), Qwen3.5-2B Q4_K_M, 32K context

- burst: **8.37 tok/s** decode, 17.4 tok/s prefill
- sustained 15 minutes: **6.31 tok/s** median, floor 6.09, battery 35 °C, no
  throttle event
- recovery after five idle minutes: 6.54 tok/s
- avatar rendered on the Serve screen costs **1.5–2.4%** of decode

A second phone adds a worker, not speed: two phones run two tasks at once, each
at one phone's pace.

## Requirements

- arm64 Android 12 or newer; 8 GB of RAM for the 2B tier
- a model downloaded on first run (1.3–2.9 GB depending on family)
- a network you trust: the app's API is the only listener and requires a key

## Install

Sideload the APK, or add the GitHub releases URL to Obtainium. F-Droid inclusion
is pending reproducible builds.

## Known limits

- Kiosk mode needs a device with no accounts configured; on a normal phone the
  app reports that Device Owner cannot be set rather than pretending otherwise.
- One model per phone at a time, one request in flight per phone.
- Pixel 7 is the only device with published measurements. Other devices are
  invited as pull requests with their own numbers.

## Licence

AGPL-3.0. The bundled inference engine is llama.cpp (MIT); model weights are
downloaded by the user and carry their own licences.
