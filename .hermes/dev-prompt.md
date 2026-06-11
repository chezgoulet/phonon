# Dev Prompt — Phonon 0.1 Blockers

## Context

Phonon is an orchestration layer that turns a cluster of used Android phones into a unified AI inference backend. Phase 1 (pool mode) is functionally complete — coordinator binary, sidecar APK, Web UI, mDNS discovery, health monitoring, OpenAI-compatible API, OIDC auth, event log, model cache — but **23 open issues remain** before the 0.1-alpha milestone can ship. These are all tagged `blocker` on the 0.1 milestone and assigned to you.

A code review (#59) surfaced defects that were closed as issues but many were never actually fixed in code. Some were fixed in PRs #88–#90 (now merged), but the majority remain open. This prompt gives you the full picture so you can work through them efficiently.

## Current State

**Latest commit:** `0a1bbbbbf808` on `main` — the PurgeStale, shutdown timeout, and Dockerfile fixes from PRs #88–#90 are now merged.

**Architecture:** One coordinator binary (`cmd/phonon-coordinator/main.go`) + one Kotlin sidecar APK (`sidecar/`). The coordinator has internal packages at `internal/{api,auth,config,discovery,health,log,model,registry,routing}`. The sidecar lives at `sidecar/app/src/main/kotlin/com/chezgoulet/phonon/`.

**CI:** golangci-lint running on Go code. No Kotlin CI yet (see #57).

## Execution Order

**Phase 1 — Fix the critical bugs first.** These are production-blocking defects that will cause crashes, incorrect behavior, or silent failures:

1. **#62 — Health monitor misuses BatteryLevel as BatteryCapacity.** The `HealthMonitor` compares `Node.BatteryLevel` (charge percentage, 0–100) against `BatteryCapacityThreshold` in config. It should compare against a derived `BatteryCapacityPct` field. This is the most dangerous bug — it misunderstands the battery telemetry model at the architectural level.

2. **#63 — Model status from heartbeat is not persisted to registry.** Heartbeat telemetry includes model status but the coordinator ignores it (`_ = node` pattern). The registry never updates `Node.ModelStatus` from incoming heartbeats, so the routing layer has stale state.

3. **#79 — Auth middleware wraps sidecar endpoints that don't have OIDC tokens.** In `main.go`, the entire `/api/v1/` prefix is behind auth middleware, but sidecar endpoints (WebSocket, pairing, health telemetry) are called by phones that don't have OIDC tokens. These need to be on a separate, unprotected route prefix like `/api/v1/sidecar/`.

4. **#67 — Inference proxy URL constructed incorrectly.** The coordinator constructs inference URLs as `http://%s/infer` using the raw IP address from phone registration. This has wrong host and wrong path.

**Phase 2 — Fix the critical data-path bugs.** These will cause incorrect routing, dropped registrations, or protocol mismatches between coordinator and sidecar:

5. **#65 — WebSocket ack type field mismatch between coordinator and sidecar.** Go's `WSAck` uses `type` as the JSON field, but sidecar's `WSAck` uses `type` too — but the Go side has mismatched semantics between `WSCommand.Type` (command type) and `WSAck.Type` (ack type). These should be distinct field names or at minimum have consistent semantics.

6. **#75 — WSHandler ack type field named inconsistently.** Related to #65 — the ack type field on the Go side is `Type` shadowing command type. Should be `AckType` or similar.

7. **#74 — mDNS discovery callback doesn't pass IP address to registry.** `discovery.RegisterCallback` receives `(deviceID, deviceModel, "")` — the IP is empty. Every downstream operation (inference proxy, WebSocket, health checks) needs the IP to connect to the phone. This means phones discovered via mDNS can't actually receive work.

8. **#68 — Coordinator URL hardcoded to 255.255.255.255 in sidecar.** The sidecar has a hardcoded broadcast address. Needs to be configurable via the APK's settings or first-run flow.

9. **#78 — Sidecar WebSocket URL path doesn't match coordinator endpoint.** The sidecar connects to `ws://$host:$port/ws` but the coordinator serves WebSocket at `/api/v1/sidecar/ws`. Connection never establishes.

10. **#87 — PhononServiceState.syncFrom() doesn't populate battery, temperature, processing, uptime, pairing, or log fields.** The Compose UI reads these fields but they're never written to. The UI shows stale/empty data.

11. **#81 — Missing R.string resources for sidecar notification channel.** Android foreground service requires NotificationChannel resources. Currently missing, likely causing crash on API 26+ or silent no-notification behavior depending on Android version.

**Phase 3 — Enhancements and refinements (medium priority):**

12. **#66 — No SSE streaming in OpenAI endpoint (returns 501).** The `/v1/chat/completions` endpoint returns 501 for stream=true. SSE streaming is required for real-time agent interactions.

13. **#71 — Random phone selection instead of health-aware round-robin.** Pool mode routing uses `rand.Intn()` to pick a phone. Should use health-aware round-robin that skips overheating, low-battery, or offline phones.

14. **#70 — Model download does not support resume (no HTTP Range).** Downloads from HuggingFace restart from zero on interruption. Large models (4+ GB) are impractical over unreliable connections.

15. **#72 — No backpressure or request queuing when all pool nodes are busy.** Requests are forwarded and fail immediately if all nodes are busy. Need a queue/429 pattern.

16. **#82 — No CORS headers on API endpoints.** Blocks local web UI development and any browser-based client. Add CORS middleware to `/api/v1/`.

17. **#73 — Event log loads all events into memory (no cap).** The JSON-lines file backend reads all events into a `[]Event` slice. For long-running clusters this will grow unbounded.

18. **#77 — OpenAI chat completion ID not unique across coordinator restarts.** `completion_id` in OpenAI responses resets to 1 on each coordinator restart. Should use UUID or coordinator-startup timestamp.

**Phase 4 — Refactors (lower priority, can be mixed into other PRs):**

19. **#91 — Sidecar uses string constants instead of sealed MessageType enum.** Kotlin constants like `CMD_MODEL_PUSH = "model_push"` are stringly-typed. Should be a sealed class.

20. **#92 — Go-Kotlin type consistency for WS message/ack structs.** Add a shared schema document and cross-language serialization test to prevent protocol drift.

21. **#83 — Registry returns mutable Node pointers instead of value copies.** Callers can mutate registry state outside the mutex. Return defensive copies.

22. **#80 — HuggingFace URL resolver assumes GGUF naming convention.** Only handles filenames matching `*.gguf`. Should be best-effort with a configurable URL override.

23. **#57 — CI pipeline and release workflow.** Build Go binaries for linux/amd64 + linux/arm64, build APK (release + debug) via Gradle, build UI dist, push Docker image to chezgoulet/phonon-coordinator on GitHub Container Registry.

## Protocol Contract (Do Not Violate)

- **Everything goes through PRs.** No direct pushes to main. Every change is a feature branch → PR → CI green → merge.
- **PR format:** Each PR must include: title prefixed with type (`fix:` / `feat:` / `refactor:` / `docs:`), body with What / Why / Risk / Verify / Rollback sections.
- **Risk field is mandatory.** If risk is Medium or High, describe what could go wrong and how to detect it.
- **Blocked? Write a decision document.** Don't guess — write up the options with trade-offs and surface it for human review. See `PHONON.md` for the full spec.
- **Single concern per PR.** Don't fix three bugs in one PR unless they're causally linked. Smaller PRs get reviewed faster.
- **Minimum test coverage on new code.** Every new function needs a test that exercises its happy path and at least one error path. Bugfix PRs should include a regression test.
- **Cross-component changes in one PR.** If a fix touches both coordinator and sidecar (e.g. #65, #78), open one PR with changes in both directories so atomicity is preserved.

## Notes on Specific Issues

- **#57 (CI)** is a prerequisite for the 0.1 release but not a prerequisite for fixing the other bugs — you can work through #62–#92 in parallel with setting up CI.
- **#62 and #63** are in `internal/health/monitor.go` — they share a root cause (health telemetry not being properly consumed by the registry). Consider fixing them in one PR.
- **#65 and #68 and #78** are all sidecar-side bugs. If you can test them together, batch them into one sidecar PR.
- **#75 is a coordinator-side rename** — just changes field names in `internal/api/ws.go` and all callers. Low risk, fast fix.
- The local `main` was recently fast-forwarded from `888950e` to `0a1bbbb`. You're up to date.

Go ahead — work through the list in order. Open a PR for each fix, tag it with the issue number, and assign it to me for review. I'll review same-day.
