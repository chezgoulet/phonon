# Gap analysis — current repo vs F-Droid main-repo acceptance

State inspected on `the build host` at `$HOME/phonon` (branch `feat/v1-features`,
remote `https://github.com/chezgoulet/phonon.git`, **0 tags**), 2026-10-17.
Requirements taken from the F-Droid docs read the same day (Build Metadata
Reference, Anti-Features, Inclusion Policy, Submitting Quick Start Guide, All
About Descriptions/Graphics/Screenshots, Reproducible Builds, buildserver
`Dockerfile`/`provision-android-ndk`).

Legend: **[BLOCKER]** F-Droid will not publish the app as-is. **[REQUIRED]**
needed for a clean merge request / Latest tab. **[REVIEW]** needs a decision or
confirmation.

## Native build (the big one)

1. **[BLOCKER] llama.cpp/ggml are prebuilt, not built from source.**
   The app's native libs live in `app/app/src/main/jniLibs/arm64-v8a/*.so` and
   are *gitignored*. They were copied in by `app/sync-native.sh` from a local
   build (`$HOME/llama.cpp/build-android-arm64`). F-Droid checks out from git
   and builds on its own server, so those files are absent there and the built
   APK would contain **no** llama.cpp at all. The inclusion policy is explicit:
   native/binary dependencies must be built from source (or from a trusted
   source); arbitrary prebuilt `.so` are not accepted.
   *Fix:* vendor llama.cpp as a git submodule pinned to a commit and build it
   via `app/app/src/main/cpp/CMakeLists.txt`, with `submodules: true` in the
   metadata. See `BUILDING.md` (Recipe A).

2. **[BLOCKER] `llama-server` is an executable, not a library.**
   `sync-native.sh` renames the `llama-server` binary to `libllama-server.so`
   so Android extracts it and it can be `exec`'d from `nativeLibraryDir`
   (`useLegacyPackaging = true`). A source build in F-Droid must reproduce that
   (build target + rename via a Gradle task / `postbuild`). It is not yet done.

3. **[REVIEW] ndkVersion pin is a release candidate.**
   `app/app/build.gradle.kts` pins `ndkVersion = "30.0.16138531"`, which is NDK
   **r30-rc1**. Stable r30 is `30.0.16248370`. Confirm which is intended and
   whether the buildserver's SDK manager offers the rc; prefer the stable one.

4. **[REVIEW] No explicit THREE-D party toolchain pin beyond the NDK.**
   CMake 3.22.1/Ninja come from AGP+SDK; fine. Record the exact llama.cpp
   commit SHA in the metadata, `BUILDING.md` and `NOTICE`.

## Build system / release engineering

5. **[BLOCKER] No Gradle wrapper.** No `gradlew`, `gradle/wrapper/gradle-wrapper.{jar,properties}`.
   The Gradle version is therefore unpinned; AGP 8.7.3 needs Gradle 8.9+.
   Commit a wrapper with `distributionSha256Sum`.

6. **[BLOCKER] No release tags.** `git tag` is empty. F-Droid builds a specific
   *commit*; `UpdateCheckMode: Tags` / `AutoUpdateMode: Version` need tagged
   release commits. Tag each release (e.g. `v0.3.0`) on the release commit.

7. **[REQUIRED] No CI workflow.** `.github/` does not exist. Drafted here at
   `.github/workflows/release.yml` (build → monotonic-versionCode check → sign →
   attach APK to a GitHub Release).

8. **[REQUIRED] No signing configuration in Gradle.** The `release` build type
   only sets `isMinifyEnabled = false` — there is no `signingConfig`, so no
   signed release APK can be produced reproducibly. Wire a release signing block
   that reads keystore material from environment variables (see the workflow).

9. **[REQUIRED] versionCode monotonicity not enforced.** `versionCode = 3` is
   hardcoded and nothing checks it increases; `docs/scope.md` lists "Monotonic
   `versionCode` enforcement in CI" as an outstanding item. The release workflow
   draft adds the guard; the app has no versionCode *scheme* (a single integer),
   which is fine, but the enforcement has to exist.

10. **[REQUIRED] Branch not merged / default branch state.** Work is on
    `feat/v1-features`; `SourceCode` should point at a stable default branch
    containing the release metadata, tags and workflow.

## Listing metadata (fastlane)

11. **[REQUIRED] No fastlane metadata in the repo.** No `fastlane/` directory.
    Drafted here (`title.txt`, `short_description.txt`, `full_description.txt`,
    `changelogs/…`). Note: F-Droid names changelogs by **versionCode**, so the
    correct file for this build is `changelogs/3.txt` (a `1.txt` is included
    only because it was explicitly requested; it is inert unless versionCode=1).

12. **[REQUIRED] No app icon.** The app has **no launcher icon at all**: no
    `mipmap-*/`/`drawable/` resources and no `android:icon` on `<application>`
    in `AndroidManifest.xml`. F-Droid needs `fastlane/.../images/icon.png`
    (typically 512×512) and the app itself should declare an adaptive icon.

13. **[REQUIRED] No screenshots.** `images/phoneScreenshots/` needs 2–4 real
    PNG/JPEG screenshots. Placeholders and guidance are in
    `fastlane/metadata/android/en-US/images/README.md`.

14. **[REQUIRED] Latest-tab prerequisites.** To appear on the F-Droid "Latest"
    tab the app needs Name, Icon, Summary, Description, Licence, a What's-New
    entry and at least one graphic — icon + screenshots + changelog cover it.

## Policy / declarations

15. **[REVIEW] "DeviceAdmin" anti-feature is a false assumption.** Verified
    against the Build Metadata Reference, the Anti-Features doc page, and the
    live f-droid.org index-v1 (4760 packages): **DeviceAdmin is not a current
    F-Droid anti-feature**, and no published app carries it. Do **not** add an
    `AntiFeatures: DeviceAdmin` key — it is outside the accepted set. The
    device-admin behaviour must instead be explained in the description and
    `MaintainerNotes` (done in the draft metadata).

16. **[REVIEW] Device-admin policy breadth draws scrutiny.** `res/xml/device_admin.xml`
    requests `force-lock`, `wipe-data`, `expire-password`, `reset-password`.
    `wipe-data`/`reset-password` are exactly the kind of capability a reviewer
    will question even without an anti-feature flag. Be ready to justify kiosk
    use, and consider trimming policies the app does not actually exercise
    (`PhononDeviceAdmin` implements no password/wipe callbacks).

17. **[REVIEW] Runtime downloads.** GGUF models are downloaded from Hugging Face
    on user request. They are *data*, not executables, so the "no downloading
    executable binaries without consent" rule is satisfied, and Qwen3.5 is
    Apache-2.0 so no **NonFreeAssets**. Confirm the download is strictly
    user-initiated (no auto-download on first run).

18. **[REVIEW] NonFreeNet question.** The app's only network dependency is
    user-chosen Hugging Face downloads. Hugging Face is not a proprietary-only
    service, and models can be side-loaded, so **NonFreeNet is not applied** in
    the draft. Flag for discussion if a reviewer disagrees.

19. **[REVIEW] Cleartext traffic + battery-optimisation exemption.**
    `android:usesCleartextTraffic="true"` and
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are declared. Both are defensible
    (LAN HTTP API; unattended appliance) and are documented in the draft
    description/MaintainerNotes, but expect reviewer questions.

20. **[REVIEW] App-ID / domain ownership.** Inclusion policy advises an
    application id derived from an owned domain; a duplicate id matching another
    party's domain is auto-rejected. `org.phononai.app` — confirm the project
    owns/controls `phononai.org` (or that no such domain implies a conflict).

## Licensing / reproducibility

21. **[REQUIRED] NOTICE completeness.** `NOTICE` exists (good) and lists
    llama.cpp/ggml as MIT, but does not reproduce the llama.cpp/ggml MIT licence
    text or the pinned commit. Add the full third-party licence texts and the
    pinned llama.cpp commit for the F-Droid build.

22. **[REQUIRED] Reproducible builds not established.** `docs/scope.md` wants
    "reproducible builds from day one" and "one signing key across GitHub and
    F-Droid". Today there is no CI, no signing config and no reproducibility
    check. NDK builds are the sensitive part (F-Droid's Reproducible Builds doc:
    NDK output differs across platforms/toolchains). To keep the project key in
    F-Droid you must reach reproducible output plus a `Binaries:`/`builds.binary`
    URL and `AllowedAPKSigningKeys` in the metadata. Otherwise F-Droid signs with
    its own key and users cannot move between channels without reinstalling.

23. **[OK] Hygiene already fine:** `local.properties`, `build/`, `.cxx/` and
    `jniLibs/` are gitignored and not tracked; `LICENSE` (Apache-2.0) and
    `NOTICE` are in the repo root; no GMS/Firebase/analytics/Tracking present;
    no ABI-split versionCode juggling needed (arm64-v8a only).

## Suggested order of work

1. Vendor + build llama.cpp from source (items 1–4).
2. Commit Gradle wrapper; add signing config; tag a release (5–6, 8).
3. Merge to the default branch; drop in fastlane metadata + real icon/screenshots (10–14).
4. Land the release workflow and generate a signed release (7, 9, 22).
5. Resolve REVIEW items (3, 15–20, 21), then `fdroid rewritemeta` + `fdroid lint`
   + `fdroid build -l` and open the fdroiddata merge request.
