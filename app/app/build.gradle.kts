plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.phononai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.phononai.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
