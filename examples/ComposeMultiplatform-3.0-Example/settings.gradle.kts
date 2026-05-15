pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }

    plugins {
        val kotlinVersion = extra["kotlin.version"] as String
        val agpVersion = extra["agp.version"] as String
        val composeVersion = extra["compose.version"] as String
        val jbComposeVersion = extra["kotlin.version"] as String

        kotlin("jvm").version(kotlinVersion)
        kotlin("multiplatform").version(kotlinVersion)
        kotlin("android").version(kotlinVersion)

        id("com.android.application").version(agpVersion)
        id("com.android.library").version(agpVersion)

        id("org.jetbrains.compose").version(composeVersion)
        id("org.jetbrains.kotlin.plugin.compose").version(jbComposeVersion)
    }
}

dependencyResolutionManagement {
    repositories {
        // Restrict mavenLocal to only blue-falcon artifacts to avoid KMP resolution issues
        // with other libraries (e.g. kotlinx-coroutines) that lack .module files in local cache
        mavenLocal {
            content {
                includeGroup("dev.bluefalcon")
            }
        }
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.weliem.blessed-bluez")
                includeGroup("com.github.weliem")
            }
        }
    }
}

rootProject.name = "BlueFalconComposeMultiplatform"
include(":androidBlueFalconExampleMP")
include(":desktopBlueFalconExampleMP")
include(":shared")