pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        jcenter()
        google()
        maven("https://dl.bintray.com/kotlin/kotlin-dev")
        maven("https://dl.bintray.com/kotlin/kotlin-eap")
    }
    resolutionStrategy {
        val kotlin_version: String by settings
        eachPlugin {
            when {
                requested.id.id == "org.jetbrains.kotlin.native.cocoapods" || requested.id.id == "kotlin-native-cocoapods" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version")
                requested.id.id == "com.android.library" ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
                requested.id.id.startsWith("org.jetbrains.kotlin") ->
                    useVersion(kotlin_version)
            }
        }
    }
}
rootProject.name = "bluefalcon"

include("library")
include("examples:android")
include("examples")

enableFeaturePreview("GRADLE_METADATA")
