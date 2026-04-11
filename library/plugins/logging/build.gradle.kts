plugins {
    kotlin("multiplatform") version "2.3.0"
}

repositories {
    google()
    mavenCentral()
}

val kotlinx_coroutines_version: String by project

kotlin {
    jvmToolchain(17)
    
    jvm()
    
    js {
        browser()
        nodejs()
    }
    
    iosSimulatorArm64()
    iosX64()
    iosArm64()
    macosArm64()
    macosX64()
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
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

group = "dev.bluefalcon"
version = "3.0.0-alpha01"
