pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Blue Falcon artifacts are consumed from the local Maven repository.
        // Publish them first with:
        //   ./gradlew -p ../../library :core:publishToMavenLocal :engines:js:publishToMavenLocal
        mavenLocal {
            content { includeGroup("dev.bluefalcon") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "blueFalconWasmExample"