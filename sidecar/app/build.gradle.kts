import java.util.Properties
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

fun keystoreProperties(): Properties? {
    val propsFile = rootProject.projectDir.parentFile?.resolve("keystore.properties")
    if (propsFile?.exists() == true) {
        val props = Properties()
        props.load(propsFile.inputStream())
        return props
    }
    return null
}

android {
    namespace = "com.chezgoulet.phonon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chezgoulet.phonon"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val props = keystoreProperties()
            if (props != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(props["storeFile"] as String)
                    storePassword = props["storePassword"] as String
                    keyAlias = props["keyAlias"] as String
                    keyPassword = props["keyPassword"] as String
                }
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // OkHttp for coordinator communication
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
