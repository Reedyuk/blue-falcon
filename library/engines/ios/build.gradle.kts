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
    
    // iOS targets
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    
    sourceSets {
        val nativeMain by creating {
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }
        
        val iosMain by creating {
            dependsOn(nativeMain)
        }
        
        val iosArm64Main by getting {
            dependsOn(iosMain)
        }
        
        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }
        
        val iosX64Main by getting {
            dependsOn(iosMain)
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
        artifactId = "blue-falcon-engine-ios",
        version = "3.0.0-alpha01"
    )
}
