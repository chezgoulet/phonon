# Bioluminescent Dreamscape — Visualization Pack Spec

**Author:** robot
**Status:** v0.9.x (iteration series)
**Target pack id:** `bioluminescent`
**Design inspiration:** Deep-sea bioluminescence — organisms that produce their own light in the dark. Think glowing jellyfish, dinoflagellate blooms in moonlit tidepools, the blue-green shimmer of a firefly squid swarm.

## Overview

A calm, organic alternative to Neon Ring's reactor-core energy. Where Neon Ring is *mechanical* (rings, orbits, nodes, arcs), Bioluminescent is *biological* — drifting motes, swaying kelp-like tendrils, soft light blooms that pulse with the device's activity.

The visual metaphor is a moonlit tidepool at night. When idle, the scene is gently glowing — sparse, slow, dim. Under load, the bioluminescence blooms: more organisms light up, the tendrils sway faster and grow brighter, and pulses of light ripple across the pool like a disturbance in the water. Heavy inference triggers a cascade — organisms flash in sequence, the pool briefly "blooms" white-hot, then settles back.

## State Mapping

The pack uses VizState to drive a single smoothed energy value (same pattern as NeonRing's `energy`):

| Energy level | Visual expression |
|---|---|
| **0.0 (idle)** | Soft, scattered blue-green pinpricks. A few drifting motes. One or two kelp tendrils swaying imperceptibly. A gentle, slow, dim scene. |
| **0.0–0.3 (light load)** | Motes become more numerous and slightly brighter. A kelp tendril or two shows visible sway. A single slow shockwave pulse drifts outward occasionally. |
| **0.3–0.6 (moderate inference)** | Bioluminescent glow patches bloom at random positions. Multiple tendrils sway at increased speed. Motes form loose groups that drift together. Shockwaves fire more frequently. |
| **0.6–0.9 (heavy inference)** | Core area — where the collective luminescence tells the user "this is working" — blooms with a white-hot flash. All motes bright and fast. Tendrils sway aggressively. Rapid cascade of light pulses across the pool. |
| **1.0 (peak load)** | The pool briefly ignites — sustained white-blue flash with particle arcs, then decay. |


Battery and thermal also have distinct expressions:

- **Low battery (< 20%, not charging):** Luminescence dims toward deep blue with a red edge. Motes slow down, drift listlessly. Fewer pulse events. If extremely low (< 10%), almost no light — just faint ghost-blue shapes.
- **Charging:** A soft green-blue "infusion" glow pulses at the edges of the pool (like light entering from the sides).
- **Overheating (> 42°C):** The palette shifts warm — bioluminescence turns amber/red. The tendrils writhe faster and more erratically.
- **Unhealthy:** A deep red pulse washes over everything periodically (like a warning bleed into the water). The pool is unsettled.

## Palette

Two palette stops that blend smoothly:

| State | Primary (motes, tendrils) | Accent (pulses, blooms) | Background |
|---|---|---|---|
| **Idle (cool)** | `#38F8C8` (teal bioluminescence) | `#60F0FF` (ice blue) | `#040B0F` (deep abyss) |
| **Active (warm)** | `#22E9A8` (spring green → the same stop as NeonRing's hotA, for visual consistency) | `#FFE066` (warm amber-gold bioluminescence) | `#091514` (slightly warm abyss) |
| **Low battery** | `#1A4C78` (dim blue) | `#1A3A5C` (faint) | `#020608` |
| **Overheating** | `#EAB308` → `#EF4444` (amber → red) | `#FF5A5A` | `#120806` |
| **Unhealthy** | Red pulse on top of active palette | `#EF4444` pulse | — |

## Elements

**The pack has 4 visual layers:**

### 1. Background — the abyss
A radial gradient from a faintly visible seabed texture (subtle noise, not pattern - just gentle colour variation from centre out) to deep black at the edges. Under load, the centre warms toward the active palette's background.

### 2. Motes — the drifting organisms (always present)
20–40 small floating circles of light. Each has:
- A fixed orbital radius and speed (very slow drift — 0.1× the current speed)
- A random phase offset so they don't all sync up
- A size (1–3px in idle, up to 6px under load)
- Alpha flickering with individual sinusoidal breathing (0.1–0.7 alpha)

Motes collect into loose groups under load — a subtle clustering effect where adjacent motes mutually brighten. Implemented as a proximity check: for each mote, sum the inverse-distance-weighted brightness contribution of all neighbors within a radius. This creates organic "blooms" without explicit grouping logic.

**Low power mode:** 10 motes, no clustering, no blooming.

### 3. Tendrils — the kelp- or jellyfish-like dancers (3–6)
Organic tendrils that sway from the bottom of the screen like long strands of algae or the trailing tentacles of a jellyfish. Each tendril:
- Grows from an anchor point at the bottom of the canvas (evenly spaced across the width)
- Has ~12 control points forming a smooth curve from anchor to tip
- Sways with two sinusoidal components: a slow fundamental and a faster harmonic (both phase-randomized per tendril)
- Under load: sway amplitude and frequency increase (up to ~3× idle)
- Glows brighter near the anchor, fades toward the tip
- Coloured with the current palette primary, alpha ~0.25 at idle up to ~0.7 at heavy load

**Low power mode:** 1 tendril, very slow, very faint (alpha 0.12).

### 4. Pulse waves — the "disturbance" events
Concentric rings of light that expand outward from a random point on the scene, triggered by:
- `isProcessing == true` — occasional pulses (every 3–8s, random interval)
- `inferenceLoad > 0.3` — more frequent pulses (every 1–2s)
- `inferenceLoad > 0.7` — rapid pulses (every 0.3–0.6s) and "cascade" mode: multiple pulses from different origins within 0.5s of each other
- `queueDepth > 10` — a sustained "bloom" pulse that holds for 1.5s instead of decaying

Each pulse expands over 1.2s, with a widening ring. A pulse has:
- A fixed origin point (randomized per pulse)
- Expanding radius from 0 to ~70% of the scene
- Alpha decaying from 0.6 to 0 over the expansion
- Line width narrowing from 3px to 0 as it expands
- Colour: palette accent at low energy, white-tinted accent at high energy

A pulse cascade under heavy load looks like a ripple in a tidepool — multiple overlapping momentary rings of light.

**Low power mode:** No pulses.

### 5. Core bloom (the "main event" under heavy load)
When inference is heavy and a pulse fires, there's a ~0.4s bloom at the centre of the scene — a bright white-blue disk that expands to ~20% of the screen width then fades. This is the pack's equivalent of Neon Ring's white-hot core glow. It only triggers when `energy > 0.6`.

## Complexity Budget

On the Kotlin side, this should sit between MatrixRain (simplest, most constrained) and CyberHud (most complex, includes a game loop). Motes are dirt cheap (float updates + circle draws). Tendrils require a path through control points each frame — moderately expensive but nowhere near CyberHud's space scene. Pulses are cheap (one arc per event). Core bloom is one gradient arc.

Target: ~200–280 lines, modestly under NeonRing's 312.

## Low-Power Mode

Same contract as existing packs: triggered by `state.lowPowerMode` (auto when battery ≤ 20% off charger, or forced via coordinator). Degrades to:
- Background + 10 dim motes (no clustering)
- 1 very faint swaying tendril
- No pulses
- No core bloom

## Config

```kotlin
override val defaultConfig = mapOf(
    "mote_count" to "30",
    "tendril_count" to "5",
    "glow_intensity" to "1.0",
    "pulse_frequency" to "1.0",
)
```

## Interface

Same as existing packs — `object BioluminescentPack : VisualizationPack` in the `com.chezgoulet.phonon.ui.packs` package. Imports VizDraw.kt helpers. Frame loop is the same `withFrameNanos` pattern as NeonRing and CyberHud.

## Why This Pack

The three existing packs cover different emotional modes:
- **Matrix Rain:** Retro, angular, cyberpunk. CRT glow. For people who want to feel like they're in a terminal.
- **Neon Ring:** Mechanical, orbital, reactor-core. For people who want to *read* the machine state at a glance.
- **Cyber HUD:** Tactical, dense, space-combat. For people who want to feel like they're piloting.

None of them are *organic*, *calm*, or *beautiful* in the way a nature documentary is. Bioluminescent fills that gap. It's the pack you'd pick when you want your phone cluster to feel alive rather than mechanical — when the visualizations should be soothing, not exciting.
