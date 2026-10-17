plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.phonon.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phonon.spike"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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

    // Keep the probe as a real file in nativeLibraryDir. Without legacy
    // packaging AGP stores uncompressed libs in the APK and the linker loads
    // them in place; the spike needs the extracted-file path llama-server
    // would use.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
