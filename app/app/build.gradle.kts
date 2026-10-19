plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.phononai.app"
    compileSdk = 35
    ndkVersion = "30.0.16138531"

    defaultConfig {
        applicationId = "org.phononai.app"
        // 29 (Android 10) rather than 31: the app targets phones that still have
        // useful life, and nothing here needs an API newer than Q at load time.
        // Every API above 29 is behind a Build.VERSION guard.
        minSdk = 29
        targetSdk = 35
        versionCode = 19
        versionName = "0.1.18"

        ndk {
            abiFilters += "arm64-v8a"
        }

        // The CPU features the shipped native build needs. Empty for the normal
        // GGML_CPU_ALL_VARIANTS build, which carries a backend for every ARMv8
        // level; a single-ISA build sets -Pphonon.requiredCpuFeatures=dotprod and
        // the app then refuses to start on a phone that lacks it. See
        // CpuFeatures.kt / NativeRequirements.
        val requiredFeatures: String =
            (project.findProperty("phonon.requiredCpuFeatures") as String?).orEmpty()
        buildConfigField("String", "NATIVE_REQUIRED_CPU_FEATURES", "\"$requiredFeatures\"")

        // Which llama.cpp this build carries. ENGINE_REV is read from the pinned
        // submodule's HEAD at configure time; a hand-written revision would drift
        // from the code that was actually compiled the moment the submodule moved.
        // Absent git or an unchecked-out submodule reports "unknown", not a guess.
        val engineRev: String = engineRevFromSubmodule()
        buildConfigField("String", "ENGINE_REV", "\"$engineRev\"")
        // The server runs llama-server --jinja and lets the model's own chat
        // template format the prompt; no template file is passed. If a custom
        // template ever becomes configurable these must carry it rather than
        // this default. Surfaced as template_mode/template_source.
        buildConfigField("String", "TEMPLATE_MODE", "\"jinja\"")
        buildConfigField("String", "TEMPLATE_SOURCE", "\"model\"")
    }

    // Signing material comes from the environment so CI can sign without a
    // keystore in the repo. A local `assembleRelease` without those variables
    // produces an unsigned APK, which is what you want for inspection.
    val releaseKeystore: String? = System.getenv("PHONON_KEYSTORE")
    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("PHONON_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PHONON_KEY_ALIAS")
                keyPassword = System.getenv("PHONON_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseKeystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Debug test instrument: libc posix_fadvise(2) shim used by /debug/evict.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // jniLibs must be extracted as real files: llama-server is exec'd from
    // nativeLibraryDir, and its sibling .so files are found via
    // LD_LIBRARY_PATH pointing at the same directory.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Device-agnostic logic that does not touch the Android framework (CPU
    // feature parsing, the memory-fit estimate, the thermal decision) is unit
    // tested on the JVM; the framework-touching wiring needs a device.
    testImplementation("junit:junit:4.13.2")
    // The per-SoC profile is stored as org.json; unit tests parse it on the JVM
    // with the real library, not the stubbed android.jar copy.
    testImplementation("org.json:json:20231013")
}

// The fadvise shim is a debug-only test instrument. Keep it out of release
// packages; the /debug/evict route is guarded by BuildConfig.DEBUG.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("lib/arm64-v8a/libphonon-fadvise.so")
    }
}

/**
 * The pinned llama.cpp submodule's HEAD, e.g. "797de63b2f", read at build time.
 *
 * The submodule lives at the repo top level while this script sits two levels
 * down, so it is looked for from the module directory and from the Gradle root.
 * Returns "unknown" when git is missing or the submodule is not checked out, so
 * configuring from a source tarball does not fail the build.
 */
fun Project.engineRevFromSubmodule(): String {
    val candidates = listOf(
        file("../../third_party/llama.cpp"),
        file("../third_party/llama.cpp"),
        rootProject.projectDir.parentFile?.resolve("third_party/llama.cpp"),
    )
    for (candidate in candidates) {
        val dir = candidate ?: continue
        if (!dir.isDirectory) continue
        val rev = try {
            val proc = ProcessBuilder(
                "git", "-C", dir.absolutePath, "rev-parse", "--short=10", "HEAD",
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0) out else ""
        } catch (_: Exception) {
            ""
        }
        if (rev.isNotBlank()) return rev
    }
    return "unknown"
}
