# Phonon app — visual design

Reference mock for the Compose UI. `mock.html` renders the four screens at
390×844; `renders/` holds the PNGs. This is the target, not a screenshot of the
current app.

## Tokens (borrowed from Hermes Vox — OLED dark is the hero)

| Token | Value |
|---|---|
| background | `#000000` |
| surface | `#0A0F16` |
| surface high | `#101724` |
| border | `#1C2A3A` |
| text | `#E6EDF7` |
| text dim | `#8B98AC` |
| cyan (primary) | `#00E5FF` |
| violet (secondary) | `#8B5CF6` |
| ok | `#34D399` |
| warn | `#FBBF24` |
| danger | `#F87171` |

## State is a hue, not a boolean

| State | Hue | Orb |
|---|---|---|
| Serving | cyan | breathing, motes drift with throughput |
| Starting / loading | violet | faster pulse |
| Paused for heat | amber | slow, dim |
| Error | red | static, ring broken |
| Stopped | dim cyan | still |

Transitions cross-fade over ~600 ms. Never a hard swap.

## Motion (borrowed from Vox's light language)

- **Orb**: soft additive glow, two concentric hairline rings, 2.4 s breathing
  cycle (scale 1.00→1.04, ring opacity 0.35→0.55), 8–12 motes drifting inside.
  Mote speed and glow intensity follow throughput; at rest they drift slowly.
- **Numbers**: cross-fade on change, 200 ms; the sparkline's last point eases in.
- **Press**: 120 ms scale to 0.98; primary action carries a faint cyan glow only
  while serving.
- **Reduce motion**: honour the system setting — keep colour changes, drop
  breathing and drift.

## Information architecture

1. **Serve** — state, model, throughput, uptime, endpoint (copyable), thermal
   policy, one primary action.
2. **Models** — installed models, active model, storage, download.
3. **Settings** — policy in plain language first, technical values secondary.
4. **Setup** (first run) — LAN vs phone-only, node name, generated API key with
   an honest warning, start serving.

Rules: no raw field names on the main screen; warnings only where a choice has a
consequence; the endpoint is one tap from the home screen because that is what
the phone is for.
