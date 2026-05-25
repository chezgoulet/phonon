# Contributing to Phonon

Phonon orchestrates a cluster of Android phones into a unified, OpenAI-compatible
inference backend. It ships as two components: a Go coordinator (single static
binary, cross-compiled to ARM64) and a Kotlin Android sidecar (foreground service
APK), plus an embedded React web UI.

This document defines the code quality standard for all three layers. It is
deliberately specific — linters are not recommendations, they are CI gates.

---

## Table of Contents

- [Development Setup](#development-setup)
- [Go Coordinator](#go-coordinator)
- [Kotlin Sidecar](#kotlin-sidecar)
- [React Web UI](#react-web-ui)
- [Cross-Cutting Standards](#cross-cutting-standards)
- [CI Pipeline](#ci-pipeline)
- [PR Workflow](#pr-workflow)
- [Code of Conduct](#code-of-conduct)

---

## Development Setup

**Prerequisites:**

| Tool | Version | Notes |
|---|---|---|
| Go | 1.23+ | `go version` to verify |
| golangci-lint | 1.62+ | https://golangci-lint.run/ |
| Java / Kotlin | JDK 21 | Android SDK at `$ANDROID_HOME` |
| Android Gradle Plugin | 8.7+ | Bundled via Gradle wrapper |
| ktlint | 1.4+ | https://ktlint.github.io/ |
| Node.js | 22+ | For the web UI |
| pnpm | 10+ | Preferred package manager |

**Quick start:**

```bash
# Clone and enter
git clone https://github.com/chezgoulet/phonon.git
cd phonon

# Go coordinator
cd coordinator
go mod download
golangci-lint run ./...
go test -race -count=1 ./...

# Kotlin sidecar
cd ../sidecar
./gradlew ktlintCheck detekt test assembleDebug

# React web UI
cd ../web
pnpm install
pnpm lint
pnpm test
pnpm build
```

---

## Go Coordinator

### Formatting

- **`gofmt`** is mandatory. Files that deviate fail CI.
- **`go vet`** must produce zero warnings.

### Linting

Use `golangci-lint` with the following configuration (place in
`coordinator/.golangci.yml`):

```yaml
linters:
  enable:
    - errcheck
    - govet
    - ineffassign
    - staticcheck
    - misspell
    - gocritic
    - whitespace
    - noctx
    - unparam
    - prealloc
    - errorlint
  disable:
    - funlen
    - cyclop
    - gocyclo

linters-settings:
  errcheck:
    exclude-functions:
      - (io.WriteCloser).Close
      - (net.Conn).Write
  gocritic:
    enabled-checks:
      - ifElseChain#3
```

### Project Layout

Follow [`golang-standards/project-layout`](https://github.com/golang-standards/project-layout):

```
coordinator/
  cmd/
    phonon-coordinator/  # main.go — small, wires dependencies
  internal/
    config/              # YAML parsing, validation, phonon types
    discovery/           # mDNS listener, phone discovery
    registry/            # node registry, state management
    routing/             # request routing, load balancing
    model/               # model cache, distribution, reconciliation
    api/                 # HTTP handlers, OpenAI-compatible API
    auth/                # OIDC validation
    web/                 # Embedded React UI assets (go:embed)
    health/              # Health checks, Prometheus metrics
  pkg/
    phonon/              # Shared types (if any become public)
  .golangci.yml
  go.mod
  go.sum
```

### Testing

- **Table-driven tests** for all `internal/` packages.
- No external test dependencies. Mock the sidecar interface — never connect
  to a real phone during unit tests.
- **Coverage targets:**
  - `internal/config/`, `internal/routing/`, `internal/registry/`: ≥70%
  - HTTP handlers: ≥50% (integration-style)
- `go test -race` must pass on every PR.
- **Naming:** `TestFuncName_Scenario` — e.g., `TestRouteToPoolGroup_OfflineNodeSkipped`.

### Error Handling

- Return errors, don't panic. Panic is reserved for `init()` failures and
  unrecoverable boot conditions.
- Wrap errors with context:
  ```go
  return fmt.Errorf("download model %s: %w", modelName, err)
  ```
- Use `fmt.Errorf("...")` for non-sentinel errors. Define sentinel errors with
  `var`:
  ```go
  var ErrModelNotFound = errors.New("model not found")
  ```
- No bare `errors.New` calls in non-sentinel paths.

### Dependencies

- `go mod` with minimal `go.sum`. Pin major versions.
- **Zero CGo dependencies.** The coordinator must compile to a static binary.
- Prefer the standard library over external packages for HTTP, JSON, crypto,
  and net.

---

## Kotlin Sidecar

### Formatting

- **`ktlint`** with default rules. Fail CI on violations.
- Alternatively, **`detekt`** with the official Kotlin style config.

### Linting

Configure `detekt` with default rules plus these adjustments:

```yaml
build:
  maxIssues: 0  # zero tolerance

style:
  MaxLineLength:
    active: false  # 120-char default is fine for Android
  NewLineAtEndOfFile:
    active: true

naming:
  MemberNameEqualsPropertyName:
    active: false  # too noisy for Android getters
  TopLevelPropertyNaming:
    active: true
```

Enable all default rule sets: `style`, `potential-bugs`, `complexity`,
`exceptions`.

### Architecture

Follow official Android architecture guidance:

```
sidecar/src/main/java/com/phonon/worker/
  sidecar/              # Cluster awareness, pairing, health telemetry
    SidecarService.kt   # Foreground service, heartbeat loop
    PairingManager.kt   # mDNS announcement + pairing handshake
    HealthReporter.kt   # Battery, thermal, storage, queue depth
    ControlServer.kt    # HTTP server for coordinator commands
    ConfigManager.kt    # Local config file management (phonon.conf)
  inference/            # Adapter layer for inference engines
    InferenceEngine.kt  # Interface/abstraction
    OlliteRTAdapter.kt  # OlliteRT over localhost HTTP
    PrimaAdapter.kt     # prima.cpp via NDK bridge (Phase 2)
  model/                # Model lifecycle
    ModelManager.kt     # Download, verify, load, unload
  network/
    MdnsAnnouncer.kt    # mDNS service announcement (NSD)
  util/
    Extensions.kt       # Android API extensions
    Preconditions.kt    # Defensive checks
```

### Concurrency

- **Coroutines** for all async work. Raw threads are prohibited.
- `Dispatchers.IO` for network/storage, `Dispatchers.Default` for CPU-bound
  work, `Dispatchers.Main` for UI updates.
- Structured concurrency: every `coroutineScope` has a parent job tied to its
  owner's lifecycle.
- Use `viewModelScope` / `lifecycleScope` for Android lifecycle-aware coroutines.

### Android Specifics

- **Foreground service** with persistent notification (required by Android 14+).
- **targetSdk 35** (Android 15). **minSdk 26** (Android 8, ~95%+ of active
  devices).
- Use Jetpack libraries (lifecycle, navigation, Room if needed).
- **No Google Play Services dependencies.** This is a hard requirement. The
  target audience runs GrapheneOS, CalyxOS, and LineageOS without GApps.
  Build-time verification must confirm zero Play Services linkage.

### Testing

- **JUnit 5** for unit tests. **MockK** for mocking.
- Compose UI tests for the (minimal) setup UI.
- `@MediumTest` for sidecar integration tests (pairing, health reporting).
- Mock the inference engine. Never call OlliteRT or prima.cpp in unit tests.

---

## React Web UI

### Formatting

- **Prettier** with defaults:
  ```json
  {
    "singleQuote": true,
    "trailingCommas": "all",
    "printWidth": 80,
    "semi": true
  }
  ```
- **ESLint** with `@typescript-eslint/strict-type-checked` config.

### TypeScript

- `strict: true` in `tsconfig.json`.
- **`any` is prohibited.** Use `unknown` for genuinely unknown types.
- Prefer **interfaces** for object shapes, **types** for unions and primitives.
- Exhaustive switch statements on discriminated unions (compiler-enforced).

### React Patterns

- **Functional components with hooks.** No class components.
- State management: **React Query** for server state (API calls). **Zustand**
  or context for UI state. No Redux.
- **No `useEffect` for derived state.** Compute derived state during render.
- Suspense boundaries at routing boundaries.

### Component Organization

```
web/src/
  components/         # Reusable UI components
    PhoneTile.tsx     # Phone status card
    GroupCard.tsx     # Group configuration editor
    PairingPanel.tsx  # Pairing flow UI
    ClusterMap.tsx    # Topology visualization
  hooks/
    useNodes.ts       # Node registry API
    useGroups.ts      # Group configuration API
    useHealth.ts      # Health data subscription
    usePairing.ts     # Pairing flow state
  pages/
    Dashboard.tsx     # Main cluster overview
    Config.tsx        # YAML configuration editor
    Settings.tsx      # Coordinator settings
  lib/
    api.ts            # Typed API client
    types.ts          # Shared TypeScript types (mirrors Go schema)
  App.tsx
  main.tsx
```

### Testing

- **Vitest** + **React Testing Library** for component tests.
- Playwright for E2E (deferred to Phase 1 pre-release gate).
- Test user-visible behavior, not implementation details. Prefer
  `getByRole` selectors.

---

## Cross-Cutting Standards

### Commit Messages (Conventional Commits)

```
<type>: <description>

[optional body — what changed and why]

[optional footer — breaking change notice, issue reference]
```

Types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `chore`, `ci`.

Examples:

```
feat: health-aware routing for pool mode
fix: mDNS listener crashes on IPv6-only network
docs: add battery safety guidance to README
refactor: extract pairing handshake into dedicated package
chore: bump golangci-lint to v2.0
```

### PRs

- Every PR must pass CI (lint + test + build for affected layers).
- Every PR must include tests for new logic, **or** a comment explaining
  why testing is impractical for that specific change.
- PR title must be a conventional commit (it becomes the squash-merge commit
  message).
- PR body must describe **what changed** and **why**.
- Reviews are required before merge:
  - Go code: reviewed by robot/vigilant or Christopher.
  - Kotlin code: reviewed by Christopher.
  - UI code: reviewed by Christopher.

### Branch Naming

```
<scope>/<description>

# Examples
feat/pool-routing
fix/mdnss-ipv6-crash
docs/setup-guide
chore/lint-config
```

### Documentation

- Public Go types have **godoc** comments.
- Public Kotlin types have **KDoc** comments.
- Public React components have brief descriptive comments.
- `CHANGELOG.md` at repo root, one entry per release, grouped by
  `feat` / `fix` / `docs` / `chore`.
- `README.md` includes: what it is, quick start, architecture diagram, link to
  full docs.

---

## CI Pipeline

Three parallel jobs:

```
Go:
  - golangci-lint run ./...
  - go vet ./...
  - go test -race -count=1 ./...
  - go build ./cmd/phonon-coordinator

Kotlin:
  - ./gradlew ktlintCheck
  - ./gradlew detekt
  - ./gradlew test
  - ./gradlew assembleDebug

React:
  - prettier --check .
  - eslint .
  - vitest run
  - vite build
```

All three must pass green before a PR can merge.

---

## PR Workflow

1. **Branch** from `main`: `feat/<description>`.
2. **Code** against the standard above.
3. **Commit** using conventional commits.
4. **Push** and open a PR against `main`.
5. **CI** runs. Fix any failures.
6. **Review**. Address feedback.
7. **Merge** via squash-merge. The PR title becomes the commit message.

---

## Code of Conduct

Be excellent to each other. Phonon is a project about making capable AI
infrastructure accessible to individuals. It exists because we believe
powerful tools should not require cloud accounts. Contributions are
welcome from anyone who shares that belief, regardless of background or
experience level.

No racism, no bigotry, no chauvinism, no platform-bashing, no harassment.
If someone reports a violation, maintainers will review and act.
