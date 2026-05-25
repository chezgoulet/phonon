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

// ── Android application ──────────────────────────────────────────────

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

        // Bundle assets from extern dependencies
        assets {
            // prima.cpp inference binary is placed here by build-prima.sh
        }
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
