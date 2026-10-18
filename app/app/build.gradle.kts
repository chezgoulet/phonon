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
        versionCode = 10
        versionName = "0.1.9"

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
}

// The fadvise shim is a debug-only test instrument. Keep it out of release
// packages; the /debug/evict route is guarded by BuildConfig.DEBUG.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("lib/arm64-v8a/libphonon-fadvise.so")
    }
}
