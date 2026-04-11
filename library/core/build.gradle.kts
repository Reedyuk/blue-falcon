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
    
    // All platforms will use this core
    jvm()
    
    js {
        browser()
        nodejs()
    }
    
    // Apple platforms
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
                implementation(kotlin("test"))
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
        artifactId = "blue-falcon-core",
        version = "3.0.0-alpha01"
    )
}
