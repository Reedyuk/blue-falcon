plugins {
    kotlin("multiplatform") version "2.3.0"
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.weliem.blessed-bluez")
            includeGroup("com.github.weliem")
        }
    }
}

val kotlinx_coroutines_version: String by project

kotlin {
    jvmToolchain(17)
    
    jvm()
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
                // Blessed library for Raspberry Pi BLE
                implementation("com.github.weliem.blessed-bluez:blessed:0.64")
                implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:4.3.2")
            }
        }
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

// Publishing configuration
group = "dev.bluefalcon"
version = "3.0.0-alpha01"

mavenPublishing {
    coordinates(
        groupId = "dev.bluefalcon",
        artifactId = "blue-falcon-engine-rpi",
        version = "3.0.0-alpha01"
    )
}
