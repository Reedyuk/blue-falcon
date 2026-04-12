plugins {
    kotlin("multiplatform") version "2.3.0"
    id("com.android.library")
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
val versionLegacy: String by project

android {
    compileSdk = 33
    namespace = "dev.bluefalcon.legacy"
    defaultConfig {
        minSdk = 24
        targetSdk = 33
    }
    lint {
        disable += "MissingPermission"
    }
}

kotlin {
    jvmToolchain(17)
    
    // Android
    androidTarget {
        publishAllLibraryVariants()
    }
    
    // iOS
    iosSimulatorArm64()
    iosX64()
    iosArm64()
    
    // macOS
    macosArm64()
    macosX64()
    
    // JS
    js {
        browser()
        nodejs()
    }
    
    // JVM
    jvm()
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }
        
        val androidMain by getting {
            dependencies {
                api(project(":engines:android"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinx_coroutines_version")
            }
        }
        
        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                api(project(":engines:ios"))
            }
        }
        
        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }
        
        val iosX64Main by getting {
            dependsOn(iosMain)
        }
        
        val iosArm64Main by getting {
            dependsOn(iosMain)
        }
        
        val macosMain by creating {
            dependsOn(commonMain)
            dependencies {
                api(project(":engines:macos"))
            }
        }
        
        val macosArm64Main by getting {
            dependsOn(macosMain)
        }
        
        val macosX64Main by getting {
            dependsOn(macosMain)
        }
        
        val jsMain by getting {
            dependencies {
                api(project(":engines:js"))
            }
        }
        
        val jvmMain by getting {
            dependencies {
                // JVM can be used for Windows and RPI via their engines
                api(project(":engines:windows"))
                api(project(":engines:rpi"))
            }
        }
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

// Publishing configuration - replaces old blue-falcon artifact
group = "dev.bluefalcon"
version = versionLegacy

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "dev.bluefalcon",
        artifactId = "blue-falcon",
        version = versionLegacy
    )
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
