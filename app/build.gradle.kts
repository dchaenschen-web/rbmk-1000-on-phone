plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dchaenschen.rbmk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dchaenschen.rbmk"
        // Android 10 (API 29) 才有 AudioPlaybackCapture
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
}

// 不依赖 AndroidX，全部用系统 API，避免 CI 拉依赖出错
dependencies {
}
