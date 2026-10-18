# Plan — closing the 0.1 audit

Source: `bugreview/findings.md` (14 findings, commit `cf1fa51`, read-only source
analysis). Findings **1, 3 and 4** are already fixed and shipped in **0.1.1**:
the battery pause undone by thermal resume, the benchmark dialling loopback on a
specific bind, and the non-atomic download start.

Eleven remain. They are grouped into three gates by what a failure costs the
person running the phone, not by how hard they are.

## How work is accepted

Every item closes with evidence, in this order of preference:

1. **Device check on the Pixel 7** for anything user-visible or timing-related.
2. **A scripted reproduction** (curl against the API, or adb) that fails before
   the change and passes after.
3. **A code-level argument** with file:line, only where neither of the above can
   reach — and it is labelled as such in the PR.

A finding is not closed because the code "looks right". The review itself was
source-only; five of its claims were explicitly unverified, and those are the
ones that need device evidence most.

## Gate A — 0.1.2: data loss and false success

These are the ones where the app lies to its operator or quietly discards a
choice. They come first because everything else can be worked around.

### A1 · Settings saves a stale snapshot (finding 2, data loss of configuration)

**What breaks.** The Settings screen holds a draft captured when it was opened
and posts every field on Save. A model selected elsewhere, or a kiosk entry that
changes the auto-computed thread count, is silently reverted — and the thread
revert also pins auto-tuning off permanently.

**Fix.** Stop posting a whole snapshot. Track which fields the user actually
edited on this screen and send only those; everything else is left to the
service's live state. The service already has the right rule for threads
(`updateSettings` compares against live settings); the UI must stop defeating it.

**Proof.** Device: open Settings, trigger a model change from the Models tab
(or finish a download while Settings is open), press Save, confirm the model is
unchanged and auto-tuning is still on. Before: model reverts.

**Risk.** Moderate — touches the settings form's state model. Keep the draft for
display, but derive the payload from dirty fields.

### A2 · `POST /server/start` accepts what it knows will fail (finding 5)

**What breaks.** A start request with a missing model file returns
`202 {"accepted":true}` while the service immediately sets `ERROR`. A client that
trusts 202 is misled for up to five minutes.

**Fix.** Validate before accepting: model file exists and is readable, exposure
rules satisfied, no start already in flight. Return `409` with the specific
reason otherwise. `POST /server/stop` should report the resulting state rather
than a bare 202.

**Proof.** Scripted: move the model file, POST `/server/start`, expect 409 and a
body naming the missing path; restore the file, expect 202 and `ready`.

### A3 · An existing file is a "completed download" with no verification (finding 6)

**What breaks.** A bare-URL download whose destination already exists returns
success without checking size or hash. A truncated file is presented as done.

**Fix.** Remove the short-circuit unless a sidecar record proves the file:
store `name.complete` containing the byte count and, when known, the hash. If a
file exists without that record, verify against the server's `Content-Length`
(HEAD) and the catalog hash when available; otherwise delete and re-download.

**Proof.** Device: truncate a model file, re-request it, confirm the downloader
replaces it rather than reporting success. Scripted check of the `.complete`
record after a normal download.

### A4 · `.` and `..` survive the filename sanitiser (finding 7)

**What breaks.** A URL path ending in `/.` or `/..` writes a `.part` beside the
models directory. No traversal outside app storage, but it produces a confusing
failure and a stray file.

**Fix.** Canonicalise the destination and require its parent to be the models
directory; reject `.`/`..` explicitly.

**Proof.** Scripted: POST a URL ending in `/..`, expect 400 and no file created.

## Gate B — 0.1.3: the control plane tells the truth

These do not corrupt data, but they make an operator distrust the app exactly
when something is wrong.

### B1 · A failing reload pins control endpoints at 503 for up to five minutes (finding 8)

**What breaks.** If a model never loads, `/settings`, `/logs`, `/model/*` and
`/server/*` answer 503 until the readiness timeout expires, including the
download progress endpoint.

**Fix.** Clear the reload flag as soon as the server reports `ERROR`, not when the
settle timeout expires; keep 503 only while a reload is genuinely in progress.
Reduce the settle ceiling to something a user would wait for.

**Proof.** Device: point the app at an unloadable model, POST a settings change,
confirm control endpoints answer within seconds (not minutes) once `/health`
says `error`.

### B2 · A settings POST can hold the config lock for ~6 seconds (finding 9)

**What breaks.** The child is torn down under `settings.configLock`, so a
concurrent settings read blocks. Not incorrect; user-visible.

**Fix.** Respond 202 before the restart, and do the kill/launch off the lock —
the service already has a single-threaded reload executor to serialise restarts.

**Proof.** Scripted: issue a settings change that restarts the server and a
`GET /settings` 200 ms later; the read must return promptly with the new values
or the old ones, never block for seconds.

### B3 · An explicit Stop is forgotten after a service restart (finding 10)

**What breaks.** `wantServer` is in-memory, so a system-killed service comes back
serving after the operator pressed Stop.

**Fix.** Persist the operator's intent (`serving_requested`) on start/stop, and
derive `wantServer` from it rather than from `autoStart` alone. Boot still
honours `autoStart`; an explicit stop overrides both until an explicit start.

**Proof.** Device: Stop serving, `adb shell am force-stop org.phononai.app`,
relaunch, confirm it stays stopped; then Start and confirm it survives the same
treatment.

### B4 · Model switches do not mark a reload in flight (finding 13)

**What breaks.** During a UI-driven model switch, `/health` can report `ready`
with the old model still serving.

**Fix.** Pass `markReload = true` from `uiSelectModel` and `uiApplyPerf`, matching
the HTTP path.

**Proof.** Device: switch models and poll `/health` every 250 ms; the state must
pass through `restarting`, never claim `ready` while the old model is serving.

## Gate C — 0.2.0: hardening

Bigger changes with design risk. They wait for a minor version.

### C1 · Proxy resource limits (finding 11)

- bounded worker pool instead of an unbounded cached pool;
- a cap on header count, not just header line length;
- a write timeout on the client socket so a stalled reader cannot hold a worker;
- a small rate limit on control endpoints, since the key is sent in clear text;
- containment check on `model_path` (must resolve inside app storage).

**Proof.** Scripted load: open N stalled connections and confirm the pool refuses
rather than exhausting; confirm the model path check rejects `/etc/passwd`.

### C2 · Device-admin policy breadth (finding 12)

**What breaks.** The receiver requests `wipe-data`, `reset-password`,
`expire-password`, `force-lock` while implementing none of the destructive ones.

**Fix.** Ship only the policies kiosk actually uses (`force-lock`, and whatever
Lock Task genuinely needs), and explain the reduction in `docs/kiosk.md`.

**Proof.** Device Owner provisioning on a wiped device (still untested) plus a
reviewer note in the F-Droid metadata.

### C3 · Per-second work on the main thread (finding 14)

**What breaks.** The health poll does a sticky broadcast registration, a
BatteryManager IPC and a directory listing every second on the main thread, in a
UI that may be pinned for hours.

**Fix.** Move the poll to `Dispatchers.IO`; cache the battery snapshot on the
thermal monitor's 30 s tick; list models on change, not on every tick.

**Proof.** Device: with the Serve screen pinned, confirm no frame drops and that
the model list still refreshes after a download.

## Deliberately not in this plan

- **TLS on the LAN.** Documented as out of scope; the API key is the boundary.
  Revisit only if someone deploys this off a trusted network.
- **Crash reporting.** The absence is a feature. The device report is the
  substitute and should keep improving instead.
- **Re-auditing what the review called sound.** Response framing, constant-time
  key comparison, the settings two-phase validation, lock ordering and the
  thermal drain were checked and hold; the plan does not spend time there.

## Decisions needed

1. **A3's `.complete` sidecar** changes the on-disk layout slightly. Acceptable?
   The alternative is re-hashing on every start, which costs seconds on a 3 GB
   file.
2. **C1's rate limit** needs a number. Proposal: 60 control requests per minute
   per client, which is far above any legitimate use.
3. **C2** reduces device-admin powers — confirm kiosk does not rely on
   `force-lock` beyond Lock Task.

## Order

Gate A first, as one release (0.1.2). Gate B as the next (0.1.3), because each
item is small and independently testable. Gate C begins only after B ships, since
its changes are the ones that can introduce new failure modes.
