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

    plugins {
        id("org.jetbrains.kotlin.android") version "1.9.24"
        id("org.jetbrains.kotlin.plugin.compose") version "1.9.24"
        id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // ✅ REQUIRED for jaudiotagger via JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "KylesMusicPlayerAndroid"

include(":app")
