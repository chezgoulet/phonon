# CI and Release Pipeline

## Overview

Phonon uses GitHub Actions for CI and release automation. Three workflows are
defined in `.github/workflows/`:

| Workflow | Trigger | Purpose |
|---|---|---|
| `ci.yml` | PR to `main`, push to `main` | Lint, test, build all layers. Upload artifacts. |
| `release.yml` | Tag push `v*` | Build coordinator binaries, attach to GitHub Release. |
| `docker.yml` | Tag push `v*` | Build and push multi-arch Docker image to Docker Hub. |

## CI Pipeline (`ci.yml`)

Three parallel jobs, with one dependency:

```
react ──► go
kotlin (parallel, no dependency)
```

### Job: `react` (Web UI)

- TypeScript type-check (`tsc`) + Vite production build
- Uploads `ui/dist/` as artifact `ui-dist`

### Job: `go` (Coordinator)

- Depends on `react` — downloads `ui-dist` artifact
- Copies UI dist to `cmd/phonon-coordinator/static/` for `go:embed`
- Runs `golangci-lint`, `go vet`, `go test -race`
- Cross-compiles for `linux/amd64` and `linux/arm64`
- Uploads binaries as artifact `coordinator-binaries`

### Job: `kotlin` (Sidecar)

- Requires Android SDK + NDK (installed on runner)
- Runs Gradle tests
- Builds `assembleDebug` and `assembleRelease` APKs
- Uploads APKs as artifact `sidecar-apks`

## Docker Workflow (`docker.yml`)

- Triggers on any tag matching `v*`
- Builds multi-arch (`linux/amd64`, `linux/arm64`) image
- Pushes to Docker Hub at `chezgoulet/phonon-coordinator`
- Tags: `{version}`, `{major}.{minor}`, `{major}`, `latest`

### Required Secrets

Set these in the GitHub repository settings:

| Secret | Purpose |
|---|---|
| `DOCKER_HUB_USERNAME` | Docker Hub account username |
| `DOCKER_HUB_TOKEN` | Docker Hub access token (not password) |

## Release Workflow (`release.yml`)

- Triggers on any tag matching `v*`
- Rebuilds Web UI and Go binaries (does not depend on CI artifacts)
- Attaches binary + SHA-256 checksum files to the release
- Detects pre-release from `-alpha` or `-beta` suffix in tag name
- Generates release notes from merged PRs

### Creating a Release

```sh
# Tag and push
git tag v0.1.0-alpha.1
git push origin v0.1.0-alpha.1

# CI + Docker + Release workflows fire automatically
```

## Secrets Reference

| Secret | Required By | How to Generate |
|---|---|---|
| `DOCKER_HUB_USERNAME` | `docker.yml` | Docker Hub account username |
| `DOCKER_HUB_TOKEN` | `docker.yml` | Docker Hub → Account Settings → Security → New Access Token |
| `ANDROID_SIGNING_KEY_STORE` | (future) | `keytool -genkey -v -keystore phonon-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias phonon` |
| `ANDROID_SIGNING_KEY_PASSWORD` | (future) | Chosen during keystore generation |
| `ANDROID_SIGNING_STORE_PASSWORD` | (future) | Chosen during keystore generation |

## Testing Locally

Dry-run individual CI steps without pushing:

```sh
# Go
go vet ./...
go test -race -count=1 ./...
CGO_ENABLED=0 go build -o /dev/null ./cmd/phonon-coordinator

# Web UI
cd ui && npm ci && npm run build

# Sidecar (requires Android SDK)
cd sidecar && ./gradlew test assembleDebug
```

## DAG

```
                        ┌─────────┐
                        │  react  │
                        └────┬────┘
                             │ ui-dist artifact
                             ▼
                     ┌───────┴───────┐
                     │  go (lint →   │
                     │  vet → test → │
                     │  build ×2)    │
                     └───────────────┘
                              │
                     coordinator-binaries artifact

┌──────────────────────┐
│  kotlin (test →      │  ← parallel, no dependency
│  build debug+release)│
└──────────────────────┘
         │
   sidecar-apks artifact
```
