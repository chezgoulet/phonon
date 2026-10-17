# Phonon UI renders — observed on device

Device: stock Pixel 7 (`panther`), serial `2A091FDH200AJ1`, Android 17.
Model: `Qwen3.5-2B-Q4_K_M.gguf`, 32,768 context, 4 threads.
Serving state: `bind_address=0.0.0.0`, API key set, keep-warm on (45 s).

These are device captures (`adb exec-out screencap -p`), not mockups.

## Screens

| File | Screen |
|---|---|
| `app-serve.png` | Serve, ready (`Serving`, 5.9 tok/s in the capture) |
| `app-paused.png` | Serve, thermal pause (`Paused — too warm`, amber, `Resume manually`) |
| `app-models.png` | Models, installed models, active model, storage, download |
| `app-settings.png` | Settings, policy-first cards |
| `app-settings-advanced.png` | Settings, endpoint + advanced technical values |
| `app-settings-advanced2.png` | Settings, backend port, load mode, switches, log, save |
| `app-setup.png` | Setup, LAN/phone-only, node name, masked generated key |

## Avatar states

The avatar is the ported Hermes Vox `AvatarView` with the default `aura` idle
theme and the existing `iris` / `A_FLAME` / `A_WAIT` / `A_BURST` archetypes.

| File | App state | Avatar |
|---|---|---|
| `avatar-stopped.png` | `stopped` | idle `aura` (`A_ORB`), dim cyan |
| `app-serve.png` | `ready` | `listening` (`A_BREATH`), green/calm |
| `avatar-generating.png` | generating | `speaking`, amp from last decode tps (`A_VOICE`) |
| `avatar-paused.png` | `thermal_pause` | `waiting` (`A_HELD`), slow constellation |
| `avatar-error-recoil.png` | `error`, first frame | `recoil` (`A_BURST`), one-shot flinch |
| `avatar-error.png` | `error`, after 480 ms | `waiting` (`A_HELD`), still |
| `avatar-states.png` | all five, cropped | comparison strip |

## Thermal and error drives

- Thermal pause: `thermal_stop_c=20`, `thermal_resume_c=19` was applied through
  `POST /settings` with the key, which made the service pause on the next
  thermal poll. The capture shows the real battery temperature (~27 °C) and the
  test thresholds. Thresholds were restored to `45/40` afterwards and the
  server returned to `ready`.
- Error: `model_path` was pointed at `.../models/missing.gguf` through
  `POST /settings`; the server entered `error` with `model not found`. The
  original path was restored and the server returned to `ready`.
- Setup: the two installed GGUF files were temporarily renamed to `.bak` so the
  first-run screen appeared, then renamed back. No model was deleted or
  committed; the final listing shows both `.gguf` files and no `.bak`.

## Measured completion rates

Measured through the app proxy on the same device, non-streaming, greedy:

| Request | Prompt t/s | Decode t/s |
|---|---:|---:|
| 26-token first completion | 24.61 | 13.96 |
| 37-token steady state, 32K context | 9.08 | 5.96 |
| 75-token probe, 4 threads | 11.75 | 5.87 |
| 35-token final check | 12.46 | 5.49 |

Steady-state runs ranged 4.2–6.0 tok/s. During a decode the server used about
3.25 cores and the big cores boosted to 2.8 GHz. The first short completion was
the 13.96 outlier; the sustained rate is the honest serving number.

The first short completion was the fastest observed. Under sustained load the
BIG/MID package sensors reached ~97/90 °C and the decode rate settled around
5.5–6.0 tok/s at `context=32768`. No reboot was performed.

## Regression checks

| Check | Result |
|---|---|
| `GET /health` reaches `ready` | 200, `"state":"ready"` |
| `GET /v1/models` without key | 401 |
| `GET /v1/models` with `Authorization: Bearer <key>` | 200 |
| `POST /settings` `{"bind_address":"0.0.0.0","api_key":""}` | 400, `"bind_address requires api_key"` |
| Completion through `/v1/chat/completions` | 200, content returned |
| Model files after tests | `Qwen...gguf` and `gemma...gguf` restored, no `.bak` |
| Keep-warm excluded from the activity signal | after 50 s idle: `keepalive_count=1`, `requests_completed` stayed 3 |

No API key value is present in this directory. Setup shows the generated key
masked; it can be revealed or copied on the device. No key is written to logs.

`git` was not run, per the build rule, so these files are written to the tree
but not committed by this agent.
