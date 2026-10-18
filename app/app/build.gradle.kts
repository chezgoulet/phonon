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
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"

        ndk {
            abiFilters += "arm64-v8a"
        }
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
}

// The fadvise shim is a debug-only test instrument. Keep it out of release
// packages; the /debug/evict route is guarded by BuildConfig.DEBUG.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("lib/arm64-v8a/libphonon-fadvise.so")
    }
}
