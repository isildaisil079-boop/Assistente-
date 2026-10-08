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
        versionCode = 10
        versionName = "0.5"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    signingConfigs {
        create("fixa") {
            storeFile = file("assistente.jks")
            storePassword = "assistente123"
            keyAlias = "assistente"
            keyPassword = "assistente123"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixa")
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
    implementation("com.google.guava:guava:33.4.0-android")
}
