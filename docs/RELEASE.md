# Release notes — v0.1.0

Draft for the first public release. Do not publish until the tag is cut; the
figures below are measured and should be re-read against `docs/performance.md`
before they are pasted anywhere permanent.

## What it is

Phonon turns an Android phone into an OpenAI-compatible inference node on your
own network. The phone owns the model and the server; a gateway on any machine
routes to one or more phones; an agent runs on a host and cannot tell the
difference from any other OpenAI endpoint.

## What this build does

- Packages `llama-server` and serves `/v1/chat/completions`, `/v1/models` and a
  `/health` endpoint on the phone, behind an API key.
- Downloads GGUF models into app storage with a size check, and offers five
  families: Qwen3.5, Gemma 4, MiniCPM5, Granite and Nemotron.
- Returns structured OpenAI `tool_calls` for every shipped family (verified on
  device with a `get_weather` call).
- Supervises the server from a foreground service, auto-starts at boot, pauses
  on temperature and resumes when it cools.
- Serves a kiosk mode through Device Owner where the device allows it, and says
  plainly when it does not.
- Exports a device report so a failing node can be debugged without guesswork.

## Measured on a Pixel 7 (8 GB), Qwen3.5-2B Q4_K_M, 32K context

- burst: **8.37 tok/s** decode, 17.4 tok/s prefill
- sustained 15 minutes: **6.31 tok/s** median, floor 6.09, battery 35 °C, no
  throttle event
- recovery after five idle minutes: 6.54 tok/s
- avatar rendered on the Serve screen costs **1.5–2.4%** of decode

A second phone adds a worker, not speed: two phones run two tasks at once, each
at one phone's pace.

## Requirements

- arm64 Android 12 or newer; 8 GB of RAM for the 2B tier
- a model downloaded on first run (1.3–2.9 GB depending on family)
- a network you trust: the app's API is the only listener and requires a key

## Install

Sideload the APK, or add the GitHub releases URL to Obtainium. F-Droid inclusion
is pending reproducible builds.

## Known limits

- Kiosk mode needs a device with no accounts configured; on a normal phone the
  app reports that Device Owner cannot be set rather than pretending otherwise.
- One model per phone at a time, one request in flight per phone.
- Pixel 7 is the only device with published measurements. Other devices are
  invited as pull requests with their own numbers.

## Licence

Apache License 2.0. The bundled inference engine is llama.cpp (MIT); model weights are
downloaded by the user and carry their own licences.

## Cutting the release (when approved)

Everything below is ready; the tag is the only step that publishes.

1. Confirm `versionCode` in `app/app/build.gradle.kts` is greater than the
   highest tagged one. CI refuses the build otherwise.
2. Confirm the repository secrets exist (names are the workflow's, and they are
   prefixed): `PHONON_KEYSTORE_BASE64`, `PHONON_KEYSTORE_PASSWORD`,
   `PHONON_KEY_ALIAS`, `PHONON_KEY_PASSWORD`. CI fails loudly if the keystore is
   missing, and verifies the signature before attaching the APK.
3. Tag the commit on `main`:

       git tag -a v0.1.0 -m "Phonon 0.1.0"
       git push origin v0.1.0

4. The workflow builds llama.cpp from the pinned submodule, runs Gradle, checks
   the tag against `versionName`, signs and attaches the APK to a draft release,
   and writes a `SHA256SUMS` file covering that APK onto the same release.
5. Verify before publishing: `apksigner verify --print-certs` on the attached
   APK, `sha256sum -c SHA256SUMS` against the downloaded APK, then install it on
   a clean device and confirm `/health` reports `ready` and a completion returns
   content.
6. Paste these notes into the release and publish.
7. Publishing sends a `repository_dispatch` to `chezgoulet/phononai.org` so the
   site regenerates against this tag. Nothing to do by hand — unless the step
   warns that the token is missing, in which case see below.

## Repository secrets

Five secrets, and only the first four are required.

| Secret | Needed for | If absent |
|---|---|---|
| `PHONON_KEYSTORE_BASE64` | signing the release APK | the build fails |
| `PHONON_KEYSTORE_PASSWORD` | unlocking the keystore | the build fails |
| `PHONON_KEY_ALIAS` | selecting the signing key | the build fails |
| `PHONON_KEY_PASSWORD` | unlocking the signing key | the build fails |
| `SITE_DISPATCH_TOKEN` | telling phononai.org to rebuild | the release still publishes; the step warns |

### SITE_DISPATCH_TOKEN

The last workflow step POSTs a `repository_dispatch` to the site repository with
`{"event_type": "app-release", "client_payload": {"tag": "<tag>"}}`. The site
workflow then checks out this repository at `<tag>` and generates the page from
it. `GITHUB_TOKEN` cannot do this: it belongs to the repository the workflow runs
in and has no rights on another one.

Create a **fine-grained personal access token** for the site repository only:

- Resource owner: `chezgoulet`.
- Repository access: **only** `chezgoulet/phononai.org`.
- Permissions: **Contents: Read and write**. Nothing else — the dispatch endpoint
  is the only thing it is used for, and this is why it is fine-grained rather
  than a classic token: a classic token scoped to one repo still carries every
  scope the account has.
- Expiry: set one. When it lapses, the release step starts warning and the site
  falls back to its daily schedule — a quiet degradation, which is why the
  warning is in the step rather than silence.

Store it as the secret `SITE_DISPATCH_TOKEN` on `chezgoulet/phonon`.

**Both failure modes are warnings, on purpose.** An absent token, or a dispatch
the site refuses, leaves the release published and exits zero. The release has
already shipped at that point; failing the workflow afterwards would mark a good
release broken, and the site has a scheduled rebuild as a backstop. The warning
in the run summary is the signal to look.

**Testers carrying a pre-reset build (0.4.x, never published) must uninstall
it first.** The public scheme restarts at `versionCode = 1`, so the old build is a "downgrade" to Android and
`adb install -r` will refuse it.

