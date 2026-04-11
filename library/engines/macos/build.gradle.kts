plugins {
    kotlin("multiplatform") version "2.3.0"
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

repositories {
    google()
    mavenCentral()
}

val kotlinx_coroutines_version: String by project

kotlin {
    jvmToolchain(17)
    
    // macOS targets
    macosArm64()
    macosX64()
    
    sourceSets {
        val macosMain by creating {
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }
        
        val macosArm64Main by getting {
            dependsOn(macosMain)
        }
        
        val macosX64Main by getting {
            dependsOn(macosMain)
        }
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
}

// Publishing configuration
group = "dev.bluefalcon"
version = "3.0.0-alpha01"

mavenPublishing {
    coordinates(
        groupId = "dev.bluefalcon",
        artifactId = "blue-falcon-engine-macos",
        version = "3.0.0-alpha01"
    )
}
