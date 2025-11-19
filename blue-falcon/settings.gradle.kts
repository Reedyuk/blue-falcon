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

include(":blue-falcon-core")
include(":blue-falcon-engine-android")
include(":blue-falcon-engine-darwin")
include(":blue-falcon-engine-js")
include(":blue-falcon")
