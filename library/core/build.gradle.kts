plugins {
    kotlin("multiplatform") version "2.3.0"
    id("com.android.library")
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

repositories {
    google()
    mavenCentral()
}

val kotlinx_coroutines_version: String by project
val versionCore: String by project

android {
    compileSdk = 33
    namespace = "dev.bluefalcon.core"
    defaultConfig {
        minSdk = 24
        targetSdk = 33
    }
}

kotlin {
    jvmToolchain(17)

    // JVM
    jvm()

    // JavaScript / WebAssembly
    js {
        browser()
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi()

    // Apple – iOS
    iosSimulatorArm64()
    iosX64()
    iosArm64()

    // Apple – macOS
    macosArm64()
    macosX64()

    // Apple – tvOS
    tvosArm64()
    tvosX64()
    tvosSimulatorArm64()

    // Apple – watchOS
    watchosArm32()
    watchosArm64()
    watchosX64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    // Linux
    linuxX64()
    linuxArm64()

    // Windows (MinGW native)
    mingwX64()

    // Android
    androidTarget {
        publishAllLibraryVariants()
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }
        
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.extra["kotlinx_coroutines_version"]}")
            }
        }
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

// Publishing configuration
group = "dev.bluefalcon"
version = versionCore

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    
    coordinates(
        groupId = "dev.bluefalcon",
        artifactId = "blue-falcon-core",
        version = versionCore
    )
    
    pom {
        name.set("Blue Falcon Core")
        description.set("Core module for Blue Falcon - A Bluetooth Low Energy Kotlin Multiplatform library")
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
