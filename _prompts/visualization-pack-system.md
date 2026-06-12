# Claude Fable: Implement Phonon Visualization Pack System

You are implementing a visualization pack system for the Phonon distributed Android inference cluster. You will write Go (coordinator), Kotlin/Compose (Android sidecar app), and React/TypeScript (coordinator Web UI) code. Existing repo at `chezgoulet/phonon`.

## What this system does

Operators upload visualization theme packs to the coordinator and push them to all paired phones. Phones render one pack at a time — a `VizState` object with normalized device telemetry (battery, temp, inference load, spatial position, peer info) drives each pack's animations. The coordinator Web UI provides a drag-and-drop canvas where operators position labeled phone cards to match their real-world layout — this enables proximity-aware visualizations in the packs. Each phone can optionally display a large translucent number overlay so operators can map phones to their widget representations.

## Repo structure

```
phonon/
  cmd/phonon-coordinator/main.go       # Go entry point
  internal/api/ws.go                   # WebSocket command dispatcher
  internal/api/pairing_api.go          # Existing REST handler patterns
  internal/registry/                   # Node registry
  sidecar/app/src/main/kotlin/com/chezgoulet/phonon/
    models.kt                          # CommandType sealed class
    PhononService.kt                   # Foreground service, telemetry state
    coordinator/CoordinatorClient.kt  # WebSocket client, handleCommand
    ui/
      PhononCompanionApp.kt            # 3-tab app (Status / Log / Visualizer)
      PhononServiceState.kt            # Mutable state holder
      screens/
        InferenceVisualizer.kt         # Current canvas visualizer (REPLACE)
  ui/
    src/
      App.tsx                          # Coordinator Web UI root
      components/
        Dashboard.tsx, PhoneCard.tsx, HealthDetail.tsx, etc.
      lib/api.ts                       # API client
```

## Part 1: Sidecar Visualization Framework

### 1.1 Create `ui/VizState.kt`

A data class that every pack receives. Fields:

| Field | Type | Source |
|---|---|---|
| deviceId | String | app.deviceId |
| displayNumber | Int? | null = hidden, set via viz_show_numbers command |
| displayNumberFlash | Boolean | brief full-opacity flash trigger |
| activeThemePack | String | current pack id |
| isProcessing | Boolean | service.isProcessing |
| tokensPerSecond | Float | compute from last inference (inference tokens / elapsed ms) |
| inferenceLoad | Float 0.0–1.0 | normalize tokens/sec against device max |
| batteryLevel | Int 0–100 | service.batteryLevel |
| batteryTemperature | Float °C | service.batteryTempC |
| isCharging | Boolean | service.isCharging |
| isHealthy | Boolean | battery>15 && temp<45 && queueDepth<50 |
| workload | Float 0.0–1.0 | composite: 0.5*(inferenceLoad) + 0.3*(queueDepth/50) + 0.2*(1-batteryLevel/100) |
| queueDepth | Int | heartbeat queue depth |
| position | DevicePosition? | from coordinator viz_arrangement command, null if unplaced |
| peerStates | List<PeerState> | devices in same arrangement grid (simplified state) |
| peerCount | Int | peerStates.size |
| coordinatorMode | String | "pool", "standby", "inference", "update" |
| lastHeartbeatAgo | Long | seconds since last successful heartbeat |
| themeConfig | Map<String,String> | pack-specific overrides from coordinator |
| lowPowerMode | Boolean | batteryLevel≤20 && !isCharging |

Create `DevicePosition(x:Float, y:Float, gridSize:Int)` and `PeerState(deviceId, displayNumber, position, batteryLevel, isProcessing, isHealthy)`.

### 1.2 Create `ui/VisualizationPack.kt`

Interface:

```kotlin
interface VisualizationPack {
    val id: String; val name: String; val description: String
    val author: String; val version: String
    val defaultConfig: Map<String, String>
    fun onActivate(); fun onDeactivate()
    @Composable fun Render(state: VizState, modifier: Modifier)
}
```

### 1.3 Create `ui/ThemeEngine.kt`

Object that:
- Registers all compiled packs in a `Map<String, VisualizationPack>` (NeonRing, MatrixRain, CyberHud)
- Holds `activePackId: String by mutableStateOf("neon-ring")`
- Holds `isDisplayNumberVisible: Boolean by mutableStateOf(false)`
- Holds `arrangement: List<DeviceArrangementEntry> by mutableStateOf(emptyList())`
- Exposes `@Composable fun PackSurface(state: VizState)` — renders the active pack's `Render()` composable, then overlays the display number on top if `isDisplayNumberVisible`
- Methods: `activatePack(id)`, `applyArrangement(entries)`, `setShowNumbers(visible)`, `flashNumber()`
- On activate: calls `onActivate()`, on switch: calls `onDeactivate()` on old, `onActivate()` on new
- The display number overlay: center-screen, large bold monospace font at 15% opacity, with 2s breathing glow animation. Flashing triggers 0.5s to full opacity then fade back.

### 1.4 Add command types to `models.kt`

Add to the `CommandType` sealed class:
- `VizSwitch("viz_switch")`
- `VizConfig("viz_config")`
- `VizArrangement("viz_arrangement")`
- `VizShowNumbers("viz_show_numbers")`

Also add them to `entries()`.

### 1.5 Handle commands in `CoordinatorClient.kt`

In `handleCommand()`, add cases for the new types that parse their respective payloads and call `ThemeEngine` methods.

### 1.6 Continuous VizState update

In `PhononService.kt`, create a `computeVizState()` coroutine that runs at ~10fps (100ms loop), reading service fields and constructing `VizState`. The `PhononServiceState.syncFrom()` already reads most fields; you can either run this in the service scope or expose it through the state object. The key: VizState needs to be observable by Compose (mutable state object or derived from the service's existing mutable fields).

Add to `PhononServiceState.kt`:
- `displayNumber: Int? by mutableStateOf(null)`
- `activePackId: String by mutableStateOf("neon-ring")`
- `peerStates: List<PeerState> by mutableStateOf(emptyList())`
- `position: DevicePosition? by mutableStateOf(null)`
- `coordinatorMode: String by mutableStateOf("pool")`

### 1.7 Wire ThemeEngine into PhononCompanionApp.kt

The Visualizer tab (index 2) should render:
```kotlin
ThemeEngine.PackSurface(state = state.toVizState())
```
Remove the old `InferenceVisualizer` call. Keep the tab structure intact.

Delete `ui/screens/InferenceVisualizer.kt` — its content is replaced by `NeonRingPack.kt`.

## Part 2: Coordinator WebSocket commands (Go)

In `internal/api/ws.go`, add to constants:

```go
const (
    CmdVizSwitch      = "viz_switch"
    CmdVizConfig      = "viz_config"
    CmdVizArrangement = "viz_arrangement"
    CmdVizShowNumbers = "viz_show_numbers"
)
```

Add send methods on `WSHandler`:
- `SendVizSwitch(deviceID, packID string) (string, error)`
- `SendVizConfig(deviceID, packID string, config map[string]string) (string, error)`
- `SendVizArrangement(deviceID string, entries []ArrangementEntry) (string, error)`
- `SendVizShowNumbers(deviceID string, visible bool) (string, error)`
- `BroadcastVizSwitch(packID string)` — iterate `ConnectedDevices()`, send to each
- `BroadcastVizArrangement(entries []ArrangementEntry)` — send to all
- `BroadcastVizShowNumbers(visible bool)` — send to all

Define `ArrangementEntry` struct: `{DeviceID string, DisplayNumber int, Position Position{X,Y float64}}`.

## Part 3: Coordinator REST API for packs

Create `internal/api/viz_api.go`. Pattern follows `pairing_api.go`. Register routes under `protectedMux` in main.go (behind auth middleware).

| Method | Path | Handler |
|---|---|---|
| GET | /api/v1/viz/packs | List built-in packs from a manifest (return JSON array of id, name, desc, author, version, defaultConfig) |
| POST | /api/v1/viz/device/{deviceId}/switch | Body: `{ "pack_id": "..." }`, calls WSHandler.SendVizSwitch |
| POST | /api/v1/viz/switch | Body: `{ "pack_id": "..." }`, calls BroadcastVizSwitch |
| POST | /api/v1/viz/device/{deviceId}/config | Per-device config override |
| POST | /api/v1/viz/arrangement | Body: `{ "entries": [{ "device_id": "...", "number": 1, "position": {"x": 0.1, "y": 0.5} }] }`, stores in-memory and broadcasts |
| POST | /api/v1/viz/show-numbers | Body: `{ "visible": true }`, broadcasts to all |

The rendered pack list comes from a simple JSON file or hardcoded map. For now, hardcode the three built-in packs. Future: uploaded packs would be stored as files on disk.

## Part 4: Coordinator Web UI — Arrangement Widget

Create `ui/src/components/ArrangementWidget.tsx`.

### Layout

- Above canvas: toolbar with **Save Arrangement**, **Reset**, **Auto-Arrange**, **Show Numbers** toggle switch, and a device count badge
- Canvas: 16:9 aspect ratio, dark background with subtle grid lines (dark-on-dark CSS grid pattern)
- Each phone card is a draggable `div` on the canvas using pointer events (no drag library needed — track `pointerdown/move/up`)

### Phone card on canvas

Each card shows:
- A circle badge with the display number (large, bold, white on accent background)
- Device name below the badge
- Border color: green (`#22C55E`) = online, amber (`#EAB308`) = paired, gray = disconnected
- On hover: tooltip with model, battery, temp, IP, state

### Drag & Drop

- `pointerdown` on a card: start drag, record offset from card origin
- `pointermove` on canvas: clamp position to canvas bounds, update card position
- `pointerup`: position applied
- Shift+drag: disables grid snapping
- Cards have z-index management — dragging card goes to top
- Grid snapping: 20px grid; positions snap to nearest grid intersection unless shift is held

### Auto-arrange

Distributes all positional cards in a balanced grid layout within the canvas (e.g., 3 columns, wrap to next row).

### Save

On Save Arrangement click:
- Collect all card positions normalized to 0.0–1.0 across canvas dimensions
- Map device IDs to display numbers (1-indexed, assigned in order of card creation or from previous arrangement)
- POST to `/api/v1/viz/arrangement`

### Data loading

On mount, fetch from `/api/v1/viz/arrangement` endpoint (or cluster nodes endpoint for device list). Merge with current nodes. Unpositioned devices appear in a sidebar list — drag from sidebar onto the canvas to place them.

## Part 5: Coordinator Web UI — Pack Manager

Create `ui/src/components/VizPackManager.tsx`.

### Pack list

Grid of pack cards showing: name, description, author, version, a color swatch representing the pack's primary color, and a "Set Active" button. Currently active pack has a distinct border (accent color).

### Upload zone (future)

A dashed-border drag-and-drop zone for ZIP upload. Shows "Upload Theme Pack (coming in a future update)" for now. The infrastructure is there on the backend but the compile-time packs mean no upload needed yet.

### Device selector

A dropdown or multi-select below the pack list: "Apply to: [All Devices] [device: Nexus-6P-001] [device: Pixel-7-002]" — sends the viz_switch command to the right target.

## Part 6: Theme Packs (3 packs — THIS IS THE CREATIVE CORE)

Each pack lives at `sidecar/app/src/main/kotlin/com/chezgoulet/phonon/ui/packs/<Name>Pack.kt` and implements `VisualizationPack`. Write these with care — they are the feature's showcase. They should be visually beautiful, technically interesting, and clearly distinct from each other. Each must handle `lowPowerMode` gracefully.

All values not explicitly specified are up to you. Be creative. Make them gorgeous.

### 6.1 Neon Ring Pack (`NeonRingPack.kt`)

**Replace the existing `InferenceVisualizer.kt` file — this IS the new InferenceVisualizer.**

**Theme:** Synthwave neon — cyan, magenta, green rings on deep indigo (#0F0A2E background).

**Must implement:**
- Two concentric neon rings at center, drawn with `drawCircle` + `Stroke`
- Ring dash patterns (use path effect or manual dash rotation) that rotate at different speeds (outer fast, inner slow)
- Four orbiting particle nodes (small circles) connected to center by thin colored lines that fade along their length
- Central glow that pulses with `inferenceLoad` (grows when processing, shrinks when idle)
- During processing: expanding pulse rings emanate from center at ~1s intervals
- Peripheral glyph rain: deterministic set of characters cycling around the outer ring perimeter
- Display number (when active): large translucent number centered inside the rings
- Low-power mode: static rings only, no particles, no glyphs, no pulse rings
- `defaultConfig`: ring_color_primary (#38BDF8), ring_color_secondary (#D946EF), ring_color_processing (#22C55E), rotation_speed (0.8), glow_intensity (1.0)

### 6.2 Matrix Rain Pack (`MatrixRainPack.kt`)

**Theme:** Green phosphor CRT — dark (#001100) background, green (#00FF41) characters, scanlines, bloom effects.

**Must implement:**
- Full-screen columns of falling characters (katakana + latin alphanumeric). For realistic matrix effect: each column has independent speed, each character randomly changes as it falls, and the leading character is brighter (full intensity green) with trailing characters diminishing to dark green
- Character "bloom" — slight radial glow behind each character (achieved via a translucent circle `drawCircle` behind each glyph, color `base_color.copy(alpha=0.15)`)
- Rain speed maps to `workload`: idle = slow trickle (~10s per column to traverse), processing = fast cascade (~2s)
- Rain brightness maps to `batteryLevel`: 100% = full glow, 20% = very dim. Interpolate linearly.
- Rain hue shifts by `batteryTemperature`: baseline green, shifts toward yellow >35°C, toward red >42°C (use `lerp` on HSV)
- During processing: bright vertical beam sweeps down from top-center, leaving widening afterglow (brightens nearby characters, fades out ~1.5s)
- Top-right HUD overlay: small monospace text lines showing tokens/s, queue, battery%, temp°C
- Display number (when active): characters momentarily avoid the number's bounding box (they pause above it, slide around it, then resume below)
- **Subtle scanline overlay**: horizontal lines at ~80% opacity, 2px spacing, `drawLine` across full width — this should be present at all times
- Low-power mode: 30% of columns, no bloom, no beam, no HUD overlay
- `defaultConfig`: base_color (#00FF41), bg_color (#001100), scanline_opacity (0.08), fall_speed (1.0), column_density (1.0), bloom_enabled (true), hud_enabled (true)

**Performance:** Pre-render glyph characters to a tile where possible. Limit per-frame work. Use `withFrameMillis` or `LaunchedEffect` for time, not `System.currentTimeMillis()`.

### 6.3 Cyber HUD Pack (`CyberHudPack.kt`)

**Theme:** Tactical/cyberpunk heads-up display — angular brackets, wireframe perspective grid, radar sweep, telemetry gauges.

**Must implement:**
- **Background:** dark (#0A0A0F) with a wireframe perspective grid receding to a vanishing point (~60% down the screen). Grid lines radiate outward from the vanishing point. `drawLine` with very low opacity (alpha=0.12).
- **Corner brackets:** Four angular brackets (L-shapes) at each corner of the screen, inset by ~4%. Color changes: normal = cyan, processing = brackets animate toward center (shrink bracket gap from 20px to 8px) over 1s, snap back on completion; low battery = amber pulse (1s cycle); overheating = red pulse (0.5s cycle).
- **Central radar sweep:** A circle (radius ~30% of screen width) centered on screen. A sweeping line rotates around the center. The rotation period is modulated by `inferenceLoad` (idle: 4s/rev, processing: 1.5s/rev). Peer devices appear as dots on the radar at relative positions based on `state.position`. The center dot represents this device, brighter and slightly larger. During processing, small "blips" appear at center and radiate outward (ping effect).
- **Left telemetry rail:** Three vertical bar gauges stacked vertically:
  - Battery: gradient green→amber→red, animated fill based on `batteryLevel`. Animated transitions (ease-in-out over 0.3s when value changes).
  - Temperature: thermometer-style, green <35°, amber 35–42°, red >42°.
  - Inference load: waveform/oscilloscope — a scrolling line chart showing the last ~60 samples of `inferenceLoad`, scrolling right-to-left. The wave color changes: cyan when idle, green when processing.
- **Right data rail:** Text readouts in monospace:
  - Device ID (last 8 chars)
  - Tokens/s: live updating number
  - Mode: coordinator mode label
  - Uptime (formatted H:MM:SS)
  - Queue depth
- **Display number (when active):** Large stencil-cut number in lower-center, flanked by angular brackets like a military callsign (e.g., `[ 03 ]`). The brackets should have a subtle animation — a slow opening/closing breathing effect.
- **Minimap (when position is available):** Small circular minimap in top-left corner (~60px diameter). Shows all positioned devices as labeled dots. Direction lines connect this device to nearest peers.
- Low-power mode: static wireframe only (no radar sweep animation, no oscilloscope animation, no minimap), static gauges (values update but no animation), text-only rails (no oscilloscope wave)
- `defaultConfig`: bracket_color (#38BDF8), bracket_alert (#F59E0B), bracket_critical (#EF4444), grid_color (#1E3A5F, 40% opacity), radar_enabled (true), minimap_enabled (true), oscilloscope_enabled (true)

## Implementation notes

### Go side
- Follow existing patterns in `pairing_api.go` and `ws.go` — same logging, same error handling, same HTTP request structure
- `VizAPI` struct takes `*WSHandler` and `*slog.Logger`, exposes handler methods
- Register routes in `main.go` via `vizAPI.RegisterRoutes(protectedMux)` (the protected mux behind auth middleware)
- Pack manifests can be a simple hardcoded JSON or a `map[string]PackManifest` — you choose

### Kotlin/Compose side
- All `drawCircle`, `drawLine`, `drawCanvas` calls go inside `Canvas(modifier)` blocks
- Use `remember` + `derivedStateOf` for computed animation values
- Use `withFrameNanos` in `LaunchedEffect` for smooth frame-driven animations (not `System.currentTimeMillis()`)
- Compose 1.6+ — use `Snapshots` for state observation in drawing loops
- Keep per-frame work low: pre-compute glyph sets, reuse `Offset` objects, use `Float` math (not `Double`)
- All colors declared as `val` constants at file level for readability

### Web UI side
- Pure React with TypeScript — no additional libraries for drag-and-drop (native pointer events)
- Tailwind CSS for styling, matching existing `phonon-*` color tokens from `tailwind.config`
- Fetch arrangements from `/api/v1/viz/arrangement`, poll every 5s
- Positions normalized to 0.0–1.0 on both axes

## File checklist

### Create:
- `sidecar/…/ui/VizState.kt`
- `sidecar/…/ui/VisualizationPack.kt`
- `sidecar/…/ui/ThemeEngine.kt`
- `sidecar/…/ui/packs/NeonRingPack.kt`
- `sidecar/…/ui/packs/MatrixRainPack.kt`
- `sidecar/…/ui/packs/CyberHudPack.kt`
- `internal/api/viz_api.go`
- `ui/src/components/ArrangementWidget.tsx`
- `ui/src/components/VizPackManager.tsx`

### Modify:
- `sidecar/…/models.kt` — add 4 CommandType entries
- `sidecar/…/coordinator/CoordinatorClient.kt` — handle viz commands
- `sidecar/…/ui/screens/InferenceVisualizer.kt` — replace with NeonRingPack
- `sidecar/…/ui/PhononCompanionApp.kt` — wire ThemeEngine.PackSurface
- `sidecar/…/PhononService.kt` — VizState update loop
- `sidecar/…/ui/PhononServiceState.kt` — add 4 new fields
- `internal/api/ws.go` — add command constants + send methods
- `cmd/phonon-coordinator/main.go` — register viz routes
- `ui/src/App.tsx` — add Visualizations navigation tab
- `ui/src/lib/api.ts` — add viz API functions

## Constraints

- Do not delete or significantly restructure existing functionality — only add to it
- The old InferenceVisualizer.kt is replaced, but its spirit lives on as NeonRingPack
- All three packs compile into the APK — no runtime loading of unknown packs
- Coordinator authorization: viz endpoints go behind the existing auth middleware (protectedMux)
- The Web UI ArrangementWidget must not require any external npm drag-and-drop library
- Packs must handle lowPowerMode — test this logic in comments if not running
- The display number overlay is handled by ThemeEngine, not individual packs
- Battery-aware degradation applies to ALL packs (they check `state.lowPowerMode`)

## Deliverable

Implement all files above. For the Go side, ensure `go build ./...` and `go test ./...` pass. For the Kotlin side, ensure the project compiles via `./gradlew assembleDebug`. For the Web UI, ensure `npm run build` succeeds.

The three theme packs should be the star of the show. Spend time on them. They need to look gorgeous, feel distinct, and demonstrate the full power of the standard primitives system.
