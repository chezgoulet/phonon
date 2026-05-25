plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
