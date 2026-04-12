import java.util.*
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.3.0"
    id("com.android.library")
    // Root module should not publish - only submodules (core, engines, plugins, legacy) publish
    // This prevents duplicate artifact IDs with the legacy module
}

repositories {
    google()
    mavenCentral()
    google()
    maven("https://jitpack.io")
    mavenLocal()
}

val local = Properties()
val localProperties: File = rootProject.file("local.properties")
if (localProperties.exists()) {
    localProperties.inputStream().use { local.load(it) }
}
val projectGithubUrl: String by project
val projectGithubSCM: String by project
val projectGithubSCMSSL: String by project
val projectDescription: String by project

val developerId: String by project
val developerName: String by project
val developerEmail: String by project
val group: String by project
val libraryName: String by project
val version: String by project

val kotlinx_coroutines_version: String by project

android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    namespace = "dev.bluefalcon.blueFalcon"
    defaultConfig {
        minSdk = 24
        targetSdk = 33
    }
    lint {
        disable += "MissingPermission"
    }
}

val frameworkName = "BlueFalcon"

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishAllLibraryVariants()
    }
    jvm("windows") {
        // compilerOptions is set via jvmToolchain(17)
    }
//    jvm("rpi") {
//        compilations.all {
//            kotlinOptions.jvmTarget = "1.8"
//        }
//    }
    js {
        browser {
            binaries.executable()
        }
    }
    iosSimulatorArm64()
    iosX64()
    iosArm64()
    macosArm64()
    macosX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val androidMain by getting
        val windowsMain by getting {
            dependencies {
                // No third-party dependencies - uses native Windows Bluetooth APIs via JNI
            }
        }
//        val rpiMain by getting {
//            dependencies {
//                implementation("com.github.weliem:blessed-bluez:0.38")
//            }
//        }
        val jsMain by getting
    }
}

// Root module does not publish to Maven Central
// Only the submodules (core, engines, plugins, legacy) publish with unique artifact IDs
// This prevents duplicate entries when publishing
