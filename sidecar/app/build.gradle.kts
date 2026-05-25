plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── prima.cpp NDK build ───────────────────────────────────────────────
// Cross-compiles llama.cpp fork for arm64-v8a.
// Runs only when ANDROID_NDK is set or when jniLibs output is missing.

val primaBuildScript = rootProject.projectDir.resolve("scripts/build-prima.sh")
val primaOutput = project.layout.projectDirectory
    .dir("src/main/jniLibs/arm64-v8a/libllama.so")

tasks.register<Exec>("buildPrima") {
    description = "Cross-compile prima.cpp (llama.cpp fork) for arm64-v8a via NDK"

    // Only configure if the script exists (skip on CI without NDK)
    onlyIf { primaBuildScript.exists() }

    // Run the build script from the repo root
    workingDir = rootProject.projectDir.parentFile
    commandLine("bash", primaBuildScript.absolutePath)

    // Environment — ANDROID_NDK must be set
    environment("ANDROID_NDK", providers.environmentVariable("ANDROID_NDK")
        .orElse(""))
}

// Hook into the build pipeline — if the user has ANDROID_NDK set,
// build prima.cpp before merging resources
if (System.getenv("ANDROID_NDK") != null) {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Resources") }
        .configureEach {
            dependsOn("buildPrima")
        }
}

// ── OlliteRT release fetch ─────────────────────────────────────────
// Downloads the pinned APK release, verifies SHA-256, extracts libollitert.so
// into assets/ollitert/ for bundling in the APK.

val olliteFetchScript = rootProject.projectDir.parentFile
    .resolve("sidecar/scripts/fetch-ollitert.sh")

var haveOlliteOutput = file(
    "src/main/assets/ollitert/libollitert.so"
).exists()

tasks.register<Exec>("fetchOlliteRT") {
    description = "Download, verify SHA-256, and extract libollitert.so from release APK"
    group = "phonon-build"

    onlyIf {
        olliteFetchScript.exists() && !haveOlliteOutput
    }

    workingDir = rootProject.projectDir.parentFile
    commandLine("bash", olliteFetchScript.absolutePath)

    // Output must exist after run
    doLast {
        haveOlliteOutput = file(
            "src/main/assets/ollitert/libollitert.so"
        ).exists()
        if (!haveOlliteOutput) {
            throw GradleException(
                "fetchOlliteRT completed but libollitert.so not found in assets"
            )
        }
    }
}

// Auto-run before APK packaging when output is missing
tasks.matching { it.name == "mergeReleaseAssets" || it.name == "mergeDebugAssets" }
    .configureEach {
        dependsOn("fetchOlliteRT")
    }

// ── Android application ──────────────────────────────────────────────
