# Phonon app — visual design

The app is styled in **CORE**: a thermal-readout identity — hard edges,
condensed uppercase display type, and the BENCH mark. Two grounds, light and
dark, following the system setting.

`mock.html` and `renders/` in this directory predate CORE: they describe the
Hermes-Vox-derived dark theme that shipped up to 0.1.18. They are kept as
history, not as the target. `app/app/src/main/java/org/phononai/app/ui/theme/
PhononTheme.kt` is the token source of truth.

## Tokens

| Token | Dark | Light |
|---|---|---|
| ground | `#08070A` | `#FFF8F2` |
| surface | `#121016` | `#FFFFFF` |
| surface high | `#1B1620` | `#F6ECE4` |
| ink | `#FFF7F0` | `#1A1210` |
| dim | `#A79AA6` | `#6F5F58` |
| rule | `#241F2B` | `#EADDD4` |
| primary | `#FFD166` | `#C2185B` |
| on primary | `#08070A` | `#FFF8F2` |
| accent | `#C2185B` | `#EF6C00` |
| on accent | `#FFF7F0` | `#FFF8F2` |
| ok | `#9BC53D` | `#3F6212` |
| warn | `#FFB020` | `#B45309` |
| danger | `#FF5A5F` | `#B3122E` |
| heat bands | `#3B1F6E` `#C2185B` `#FF6B00` `#FFD166` | `#6C4AE8` `#D81B60` `#EF6C00` `#FFC043` |

Screens read colours through `PhononColors`, whose properties are composable
getters over a `CompositionLocal` palette. Nothing hardcodes a hex value.

## Type

- **Archivo Black** — display, screen titles, section headings, the wordmark.
  Uppercase, tight tracking.
- **Inter** — body and supporting copy.
- **IBM Plex Mono** — every measurement and every micro-label, letterspaced and
  uppercase. Body styles carry `tnum`, so figures align in columns.

## Shape

Every radius is zero. Cards, buttons, chips, fields, meters and the switch are
rectangles; the only exceptions are 2 dp corners where a thin line would
otherwise read as a rendering artefact, and there are none today.

## Heat band strip

Four equal bands, no gaps, full-bleed, 64 dp, at the top of the Serve surface.
It is brand furniture, **not** a data display: the temperature is the labelled
meter on the thermal card, whose fill position and text carry the meaning. The
BENCH mark never sits on the bands — it carries its own ground plate.

## The BENCH mark

Three open squares joined by right-angle traces to a lamp at the centre,
`res/drawable/ic_bench_mark.xml`. Below 48 dp the compact cut drops the traces
(`ic_bench_mark_compact.xml`); the launcher icon uses the ground background, the
mark as foreground and a monochrome layer for themed icons.

## State is a hue, not a boolean

| State | Hue | Orb |
|---|---|---|
| Serving | primary | breathing, motes drift with throughput |
| Starting / loading | accent | faster pulse |
| Paused for heat | warn | slow, dim |
| Error | danger | static, ring broken |
| Stopped | dim | still |

## Motion (borrowed from Vox's light language)

- **Orb**: soft additive glow, two concentric hairline rings, 2.4 s breathing
  cycle (scale 1.00→1.04, ring opacity 0.35→0.55), 8–12 motes drifting inside.
  Mote speed and glow intensity follow throughput; at rest they drift slowly.
- **Numbers**: cross-fade on change, 200 ms; the sparkline's last point eases in.
- **Press**: 120 ms scale to 0.98.
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

## Avatar (borrowed from Hermes Vox)

`AvatarView.kt` and `VisualStyle.kt` from `hermes-vox` (Apache-2.0, same author)
are ported into `org.phononai.app.ui.avatar`. The particle system is kept
intact; only the package and the state wiring change.

State mapping:

| App state | Avatar | Reads as |
|---|---|---|
| stopped | idle theme `aura` (`A_ORB`) | still, dim |
| loading / restarting | `gather`, workload 0.6 | pulling itself together |
| ready, idle | `listening` (`A_BREATH`) | calm, receptive |
| generating | `speaking`, amp = min(1, decode_tps / 20) | busier with throughput |
| thermal_pause | `waiting` | slow, held |
| error | `recoil` once, then `waiting` | a flinch, then still |

The avatar draws its own black field with additive blending, so it reads the
same in both themes; its internal state hues are the particle system's own and
are not derived from the CORE palette.
