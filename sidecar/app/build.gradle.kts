import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── Android application ──────────────────────────────────────────────

// ── Kotlin compiler ───────────────────────────────────────────────

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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

    // Note: jvmTarget migrated to top-level kotlin.compilerOptions below
    // (kotlinOptions is removed in Kotlin 2.3)

    buildFeatures {
        compose = true
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

    // LiteRT-LM — on-device LLM inference (replaces prima.cpp JNI + OlliteRT subprocess)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.0")

    // OkHttp for coordinator communication
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
