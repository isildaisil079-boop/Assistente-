plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.assistente.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.assistente.launcher"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
