# F-Droid readiness

Current state of the repository against F-Droid main-repo acceptance. This
replaces the 2026-10-17 gap analysis; the blockers recorded there are closed in
the repository.

## Closed

- **Native code is built from source.** llama.cpp is a pinned submodule at
  `third_party/llama.cpp`, commit `797de63b2fa7396dcc8caca76834c699ac84e1c3`.
  `app/build-native.sh` builds it for `arm64-v8a` with `GGML_CPU_REPACK=ON` and
  installs the artifacts into `jniLibs`. Nothing prebuilt is committed;
  `jniLibs/` and `.native-build/` are ignored.
- **`llama-server` packaging.** `app/sync-native.sh` installs the executable as
  `libllama-server.so`, so Android extracts it and the app `exec`s it from
  `nativeLibraryDir`.
- **Gradle wrapper.** `app/gradlew` with the wrapper jar committed and the
  distribution pinned; CI invokes `./app/gradlew -p app`.
- **Version scheme.** `versionCode = 1`, `versionName = "0.1.0"`. The release
  workflow refuses a versionCode that is not greater than the highest previously
  tagged one.
- **Release pipeline.** `.github/workflows/release.yml` checks out submodules,
  installs the pinned NDK, builds the native artifacts, derives the version,
  enforces monotonicity, decodes the keystore and attaches the signed APK.
- **Default branch.** `main` and `testing` carry the release work; the metadata,
  workflow and wrapper are on the default branch.
- **Fastlane metadata.** Title, short and full description, icon, screenshots
  and a changelog for `versionCode = 1` are present.
- **App icon.** Adaptive and legacy launcher icons are in the app and
  `fastlane/.../images/icon.png` is the listing icon.

## Open

1. **NDK pin is a release candidate.** `30.0.16138531` is r30-rc1 and produced
   every measured number. The buildserver must be able to fetch it; if it
   cannot, move to stable `30.0.16248370` in `ndkVersion`,
   `ANDROID_NDK_VERSION` and the metadata together, and re-run the benchmarks
   before publishing figures.
2. **The metadata has not been submitted.** The draft is at
   [`fdroiddata/com.phononai.app.yml`](fdroiddata/com.phononai.app.yml) and is
   versioned with the app. It passes `fdroid lint` (fdroidserver 2.4.5, against
   fdroiddata's own `config/categories.yml`) with no findings. The merge request
   steps and its description are in
   [`fdroiddata/SUBMITTING.md`](fdroiddata/SUBMITTING.md); opening it needs a
   GitLab account, which the House host does not have.
3. **First buildserver run is unproven.** Everything here was built on our own
   machines and on GitHub runners. F-Droid's buildserver is a different machine
   with a different toolchain; the first run is the real test of the recipe, not
   of the app.

## Decided

- **One signature.** Reproducible builds are verified on one machine and across
  machines — the shipped v0.1.18 APK matches a rebuild of its tag in all 120
  non-signature entries ([`REPRODUCIBLE.md`](REPRODUCIBLE.md)). The metadata
  uses `Binaries:` and
  `AllowedAPKSigningKeys:` and F-Droid publishes the APK we sign. If the
  buildserver cannot reproduce it, deleting those two lines falls back to
  F-Droid's own key without changing anything else.
- **No embedded web UI.** The engine is built with `LLAMA_BUILD_UI=OFF`: the
  phone serves the API, and the buildserver has no network to fetch the UI
  assets.

## Closed since this page was written

- **Device-admin policy breadth** — `res/xml/device_admin.xml` now declares an
  empty `<uses-policies />` (0.1.4). Lock Task needs the Device Owner component,
  not the legacy policies, and the kiosk rationale is in `docs/kiosk.md`.
- **Screenshots** — refreshed for the current UI (six avatar shapes, five model
  families) and regenerated from the app on each release.
- **NOTICE** — carries the llama.cpp MIT text and the pinned revision.

## Notes

- F-Droid names changelogs by **versionCode**, so
  `fastlane/metadata/android/en-US/changelogs/1.txt` belongs to `versionCode = 1`.
- ABI is arm64-v8a only; no split versionCodes.
- DeviceAdmin is **not** an F-Droid anti-feature; do not add the key. Explain the
  behaviour in the description and `MaintainerNotes` instead.
- Models are downloaded on user request and are data, not executables; Qwen3.5,
  Gemma 4, MiniCPM5, Granite and Nemotron are all permissively licensed.
