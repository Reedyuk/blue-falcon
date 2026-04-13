pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android") {
                useModule("com.android.tools.build:gradle:8.2.2")
            }
        }
    }
}

rootProject.name = "blue-falcon"

// Include core module
include(":core")

// Include engine modules
include(":engines:android")
include(":engines:rpi")
include(":engines:js")
include(":engines:windows")
include(":engines:ios")
include(":engines:macos")

// Include legacy compatibility layer
include(":legacy")

// Include plugin modules
include(":plugins:logging")
include(":plugins:retry")
include(":plugins:caching")