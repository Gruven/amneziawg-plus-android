import com.android.build.api.dsl.SettingsExtension

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.settings") version "8.13.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "amneziawg-android"

include(":tunnel")
include(":ui")

configure<SettingsExtension> {
    buildToolsVersion = "35.0.0"
    compileSdk = 35
    minSdk = 21
    ndkVersion = "26.1.10909125"
}
