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
val versionEngines: String by project

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
version = versionEngines

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "dev.bluefalcon",
        artifactId = "blue-falcon-engine-rpi",
        version = versionEngines
    )
    
    pom {
        name.set("Blue Falcon Raspberry Pi Engine")
        description.set("Raspberry Pi BLE engine for Blue Falcon using BlueZ DBus")
        url.set("https://github.com/Reedyuk/blue-falcon")
        
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        
        developers {
            developer {
                id.set("Reedyuk")
                name.set("Andrew Reed")
                email.set("andrewreed.uk@gmail.com")
            }
        }
        
        scm {
            url.set("https://github.com/Reedyuk/blue-falcon")
            connection.set("scm:git:git://github.com/Reedyuk/blue-falcon.git")
            developerConnection.set("scm:git:ssh://git@github.com/Reedyuk/blue-falcon.git")
        }
    }
}

// Signing configuration
signing {
    // Only sign when publishing to Maven Central, not for local builds
    setRequired {
        gradle.taskGraph.allTasks.any { 
            it.name.contains("publishTo") && it.name.contains("MavenCentral") 
        }
    }
}
