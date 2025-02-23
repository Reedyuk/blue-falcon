pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "blue-falcon"

include(":blue-falcon-android")
include(":blue-falcon-core")
include(":blue-falcon-darwin")
include(":blue-falcon-js")
