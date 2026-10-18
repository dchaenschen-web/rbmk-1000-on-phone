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
        versionCode = 2
        versionName = "1.1"
    }

    // ---------------------------------------------------------------- 固定签名
    // 问题：GitHub Actions 每次跑都是干净环境，AGP 会自动生成一个新的 debug 密钥，
    // 于是每次构建的 APK 签名都不同 —— Android 拒绝覆盖安装
    // （INSTALL_FAILED_UPDATE_INCOMPATIBLE），必须先卸载旧版。
    //
    // 解法：仓库里放一把固定的调试密钥，构建时复用。
    // 密钥由 CI 首次运行时生成并自动提交回仓库，之后永远不变。
    val fixedKeyStore = rootProject.file("keystore/debug.keystore")

    signingConfigs {
        if (fixedKeyStore.exists()) {
            create("fixed") {
                storeFile = fixedKeyStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (fixedKeyStore.exists()) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            if (fixedKeyStore.exists()) {
                signingConfig = signingConfigs.getByName("fixed")
            }
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
