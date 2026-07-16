pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.vanniktech.maven.publish") version "0.34.0"
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

// Include peripheral / GATT server module
include(":peripheral")

// Include engine modules
include(":engines:android")
include(":engines:rpi")
include(":engines:js")
include(":engines:windows")
include(":engines:ios")
include(":engines:macos")
include(":engines:macos-jvm")

// Include legacy compatibility layer
include(":legacy")

// Include plugin modules
include(":plugins:logging")
include(":plugins:retry")
include(":plugins:caching")
include(":plugins:nordic-fota")
include(":plugins:clone")
include(":plugins:broadcast")