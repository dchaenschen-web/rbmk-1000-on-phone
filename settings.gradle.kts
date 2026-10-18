// 关键：Android Gradle Plugin 不在 Gradle 官方插件门户，而托管在 Google 的 Maven 仓库。
// 不在这里声明 google()，构建会报：
//   Plugin [id: 'com.android.application', version: '8.5.2'] was not found
//   Searched in the following repositories: Gradle Central Plugin Repository
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// 工程依赖（AGP 的传递依赖、kotlin-stdlib 等）也来自这两处
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rbmk-1000"
include(":app")
