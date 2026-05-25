plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

android {
    namespace = "com.chezgoulet.phonon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chezgoulet.phonon"
        minSdk = 29  // Android 10 — covers all phones worth using for inference
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // No Play Services — GrapheneOS compatible
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // OkHttp for HTTP + WebSocket — only external dependency
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Stdlib only — no Play Services, no Moshi/Gson (use org.json from the JDK)
}
