# Phonon UI renders — observed on device

Device: stock Pixel 7 (`panther`), serial `2A091FDH200AJ1`, Android 17.
Model: `Qwen3.5-2B-Q4_K_M.gguf`, 32,768 context, 4 threads.
Serving state: `bind_address=0.0.0.0`, API key set, keep-warm on (45 s).

These are device captures (`adb exec-out screencap -p`), not mockups.

## Screens

| File | Screen |
|---|---|
| `app-serve.png` | Serve at the final LAN bind after the two measured benchmarks: tier line, warm result `7.0 tok/s`, 28.6 °C. The request counter reads 0 because the final APK install restarted the service; benchmark history persisted |
| `app-serve-idle.png` | Serve on first launch after data clear: `Pixel 7 · 8 GB · tier S`, 2B / 32K / 4, loopback endpoint, no benchmark yet |
| `app-paused.png` | Serve, thermal pause (`Paused — too warm`, amber, `Resume manually`) |
| `app-models.png` | Models, scrolled: download card with empty GGUF URL and hint |
| `app-models-top.png` | Models, top: storage, active model and the second installed model |
| `app-settings.png` | Settings, top: detected Device card, auto-config line, Export device report, then policy cards |
| `app-settings-advanced.png` | Settings, kiosk section and the start of advanced values |
| `app-settings-advanced2.png` | Settings, empty default download URL, load mode, switches, log, save |
| `app-settings-kiosk.png` | Settings, Kiosk / appliance mode: Device Owner `no`, provisioning command |
| `app-setup.png` | Setup, LAN/phone-only, node name, masked generated key |

## v1 features captured 2026-10-17

App 0.3.0, after `pm clear` and auto-config, then two benchmark runs and a
device-report export on the same Pixel 7.

- **Tier on Serve.** The model line now starts with
  `Pixel 7 · 8 GB · tier S`. `app-serve-idle.png` is the first launch after
  data clear: model `Qwen3.5-2B-Q4_K_M.gguf`, 32K context, 4 threads, alias
  `qwen3.5-2b`, loopback bind (the LAN bind was restored after the captures).
- **Benchmark card.** `app-serve.png` shows the second, warm run:
  `7.0 tok/s decode`, prefill 6.5 tok/s, TTFT 0.7 s, 28.3 °C, peak 1.80 GB.
  The same card is shown on Serve while serving; running it does not block the
  screen. The cold run was 5.9 tok/s at 3.3 s TTFT, so the two captures in
  `../../docs/reports/` show the variance rather than one number.
- **Device report.** `app-settings.png` shows the Device card with the detected
  tier, the SoC/Android/ABI line and **Export device report**. The preview
  dialog (exactly what leaves the phone) is captured in
  `../../docs/reports/pixel-7-report-preview.png`; the checked-in text is
  `../../docs/reports/pixel-7-report.txt`.

## Polish pass

1. **Idle throughput.** Before the first request the card reads `idle` and
   `Awaiting first request`; there is no leading em dash. After a request it
   switches to `LAST THROUGHPUT` with the measured value in the muted text
   colour and the sparkline retained. `app-serve-idle.png` and
   `app-serve.png` show both states.
2. **Serve spacing.** A compact `RECENT ACTIVITY` row (completed count and
   relative last-request age) sits below the benchmark card. Serve now scrolls
   above the fixed primary action: the benchmark result block and the activity
   row together are taller than the content area on a 6.1" phone, and the old
   weight-spacer layout clipped the activity row behind the button.
3. **Avatar size.** The `AvatarView` slot is 156 dp. The particle body is
   drawn at `0.32 × min(width, height)`, so the rendered cloud is ~85 dp and
   still legible; the slot was reduced from 172 dp to make room for the
   benchmark card. The 1.55 × halo stays inside the view bounds.
4. **Download URL.** `Settings.DEFAULT_MODEL_URL` is now empty. The Models
   card shows an empty `GGUF URL` field, the hint “Paste a direct GGUF link.
   Nothing downloads until you press Download.”, and a disabled Download
   button. The pre-existing persisted development URL
   (`http://127.0.0.1:9000/...`) is read as unset by a migration getter, so
   upgrading from the old build shows the same empty state without rewriting
   preferences. The advanced “Default download URL” field is optional and empty
   in `app-settings-advanced2.png`.

## Kiosk state observed

`dpm set-device-owner` was attempted against the installed receiver. It failed
because the phone has accounts:

```
$ adb -s 2A091FDH200AJ1 shell dpm set-device-owner org.phononai.app/.PhononDeviceAdmin

Exception occurred while executing 'set-device-owner':
java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device.
```

`dpm list-owners` returned no owners. The Settings card therefore reports
**Device Owner: no** and **Can provision now: no**, and displays the exact
provisioning command and the account/secondary-user warning instead of
enabling kiosk controls. Kiosk mode was **not** entered and is not claimed.
Provisioning steps, QR payload, and per-OEM battery notes are in
[`../../docs/kiosk.md`](../../docs/kiosk.md).

## Screen-on while serving

Foreground, state `ready`:

```
Window #9 ... org.phononai.app/org.phononai.app.MainActivity
    fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH ...

Wake Locks: size=2
  PARTIAL_WAKE_LOCK      'Phonon::llama-server' ...
  SCREEN_BRIGHT_WAKE_LOCK 'WindowManager/displayId:0' ON_AFTER_RELEASE ...
      ws=WorkSource{10415 org.phononai.app}
```

After `KEYCODE_HOME`, the launcher became the focused app, the
`SCREEN_BRIGHT_WAKE_LOCK` disappeared, and only the partial wake lock remained.
Twenty seconds later `/health` was still `ready` with the same server pid, the
app process was alive, and `libllama-server.so` was still running. That is the
current behaviour with the activity backgrounded: CPU stays awake, screen-on
does not. Kiosk pins the activity so this does not happen in the appliance role,
but kiosk could not be entered on this phone.

`mStayOn=true` was also printed, but this unit has
`mStayOnWhilePluggedInSetting=2` (stay awake while charging) from earlier
provisioning, so `mStayOn` alone does not attribute the screen to the app. The
window flag and the app-attributed wake lock do.

## Thermal and error drives

- Thermal pause: `thermal_stop_c=20`, `thermal_resume_c=19` was applied through
  `POST /settings` with the key, which made the service pause on the next
  thermal poll. The capture shows the real battery temperature (~28 °C) and the
  test thresholds. Thresholds were restored to `45/40` afterwards and the
  server returned to `ready`.
- Setup: the two installed GGUF files were renamed to `.bak` with the server
  stopped, and the app was force-stopped and relaunched to clear saved UI
  state. The first-run screen was captured, the files were renamed back, and
  the server returned to `ready`. No model was deleted or committed; the final
  listing shows both `.gguf` files and no `.bak`.

## Measured completion rates

Measured through the app proxy on the same device, non-streaming, greedy:

| Request | Prompt t/s | Decode t/s |
|---|---:|---:|
| 24-token final capture completion | 12.74 | 4.81 |
| 24-token ocean completion | 13.18 | 6.22 |
| 24-token 2+2 completion | 12.44 | 4.60 |
| 3-token “say hello” check | 25.25 | 14.03 |

The final screenshot shows the 4.81 tok/s last-rate card. These are app-path
numbers, not raw server numbers. No reboot was performed.

## Regression checks

| Check | Result |
|---|---|
| `GET /health` reaches `ready` | 200, `"state":"ready"` |
| `GET /v1/models` without key | 401 |
| `GET /v1/models` with `Authorization: Bearer <key>` | 200, id `qwen3.5-2b` |
| `POST /settings` `{"bind_address":"0.0.0.0","api_key":""}` | 400, `"bind_address requires api_key"` |
| Completion through `/v1/chat/completions` | 200, non-empty content returned |
| `requests_completed` for one proxied completion | 0 → 1 |
| Model files after tests | `Qwen...gguf` and `gemma...gguf` restored, no `.bak` |

Final debug APK: `34,502,920` bytes, sha256
`350f92610d842bb7a2be4d3ab19317e4fc73d290cfd5003795d6048037ccc166`,
installed 2026-10-17 18:52:08.

No API key value is present in this directory. Setup shows the generated key
masked; it can be revealed or copied on the device. No key is written to logs.

`git` was not run, per the build rule, so these files are written to the tree
but not committed by this agent.
