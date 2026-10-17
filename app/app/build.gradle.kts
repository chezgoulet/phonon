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
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// The fadvise shim is a debug-only test instrument. Keep it out of release
// packages; the /debug/evict route is guarded by BuildConfig.DEBUG.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("lib/arm64-v8a/libphonon-fadvise.so")
    }
}
