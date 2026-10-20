# Mark motion: integrating MarkView

`ui/avatar/MarkView.kt` replaces the particle `AvatarView` on the Serve screen.
The motion rules are in `ui/avatar/MarkMotion.kt`, with tests in
`MarkMotionTest.kt`. `preview/mark-motion.html` is the contact sheet (open
it, or add `#light`). The renders are `preview/mark-motion.png` and
`preview/mark-motion-light.png`.

`PhononApp.kt` was not edited because another change is in flight there.
Apply the diff below once that change lands.

## API

```kotlin
MarkView(context)                          // (context, attrs) also works from XML
  .setPalette(MarkPalette(ink, dim, rule, primary, accent, danger, heat))  // ARGB ints
  .setMotionStyle(MotionStyle.BUS)         // BUS | BUILD | HEAT; pick one after review
  .setState(MarkState.Generating, tps)     // tps paces Generating; same state = retime only
  .setHeld(true)                           // static frame (the "animate while generating" setting)
  .compactOverride = null                  // null: compact below 48 dp, automatically
```

`MarkState` mirrors `AvatarKind` one for one: AvatarKind is private to
PhononApp.kt, so the view has its own enum. System reduced motion
(`ANIMATOR_DURATION_SCALE == 0`) is read on attach and on every state change.
It needs no wiring.

## Call-site diff (`app/app/src/main/java/org/phononai/app/ui/PhononApp.kt`)

```diff
@@ imports
-import org.phononai.app.ui.avatar.AvatarView
+import androidx.compose.ui.graphics.toArgb
+import org.phononai.app.ui.avatar.MarkPalette
+import org.phononai.app.ui.avatar.MarkState
+import org.phononai.app.ui.avatar.MarkView
+import org.phononai.app.ui.avatar.MotionStyle
+import org.phononai.app.ui.theme.LocalPalette

@@ ServeScreen, the Row under BenchLockup()
-            // AvatarView draws the particle body at 0.32 × the shorter side.
-            // Measured on the Pixel 7: a 140 dp slot gave a 77 dp visible orb,
-            // so 156 dp yields a ~84 dp visible cloud with the 1.55 × halo
-            // still inside the bounds. The benchmark card below needs the
-            // height; the orb stays legible at this size.
+            // The mark fills its slot (256 grid, ~6% margin each side).
             Avatar(
                 kind = kind,
-                amp = (tps / 20f).coerceIn(0f, 1f),
+                tps = tps,
                 reduced = reducedAvatar,
-                shape = AppState.presenceShape,
                 modifier = Modifier.size(156.dp),
             )

@@ replace the whole Avatar composable and the AvatarDriver class
-@Composable
-private fun Avatar(kind: AvatarKind, amp: Float, reduced: Boolean, shape: String, modifier: Modifier) {
-    val driver = remember { AvatarDriver() }
-    AndroidView(
-        ...
-    )
-}
-
-private class AvatarDriver {
-    ...
-}
+@Composable
+private fun Avatar(kind: AvatarKind, tps: Float, reduced: Boolean, modifier: Modifier) {
+    val core = LocalPalette.current
+    val palette = remember(core) {
+        MarkPalette(
+            ink = core.ink.toArgb(),
+            dim = core.dim.toArgb(),
+            rule = core.rule.toArgb(),
+            primary = core.primary.toArgb(),
+            accent = core.accent.toArgb(),
+            danger = core.danger.toArgb(),
+            heat = IntArray(4) { core.heat[it].toArgb() },
+        )
+    }
+    AndroidView(
+        modifier = modifier,
+        factory = { context ->
+            MarkView(context).apply { setMotionStyle(MotionStyle.BUS) }
+        },
+        update = { view ->
+            view.setPalette(palette)
+            view.setHeld(reduced)
+            view.setState(kind.toMarkState(), tps)
+        },
+    )
+}
+
+private fun AvatarKind.toMarkState(): MarkState = when (this) {
+    AvatarKind.Stopped -> MarkState.Stopped
+    AvatarKind.Loading -> MarkState.Loading
+    AvatarKind.Ready -> MarkState.Ready
+    AvatarKind.Generating -> MarkState.Generating
+    AvatarKind.Paused -> MarkState.Paused
+    AvatarKind.Error -> MarkState.Error
+}
```

Notes on the diff:

- **`tps`** is the raw `health?.lastDecodeTps` already in scope, not the
  `0..1` amp. The view clamps the beat itself, and repeated calls in the
  same state only retime it. The old driver needed a 0.04 amp dead-band, and
  the new one doesn't.
- **`reduced`** keeps its meaning. With "Animate avatar while generating"
  off (the default), Generating draws a static frame: all traces lit for BUS,
  all squares filled for BUILD, the level for HEAT. That frame costs nothing
  per second. Because the mark is cheap, consider making the default on.
  That is a product call and is not part of this change.
- **The style choice is `MotionStyle.BUS` in `factory`.** Change it there
  after review, or wire it to a setting.
- **Error** no longer needs the 480 ms `postDelayed` fallback. Each style's
  Error settles on its own and stops drawing.
- **After applying**, nothing calls `AvatarView.kt`, `MotionState.kt` or
  `VisualStyle.kt`. The presence-shape picker in Settings
  (`AppState.presenceShape`, `Settings.presenceShape`) also stops doing
  anything. Remove it in a follow-up, or reuse its slot as the
  `MotionStyle` picker.
- **Header size.** `BenchMark(52.dp)` in `BenchLockup` is the static
  drawable and can stay. If a ~26 dp animated mark is wanted in a header, use
  the same `Avatar` at `Modifier.size(26.dp)`. Below 48 dp it switches to the
  compact cut (no traces, filled squares) without any extra setup.
