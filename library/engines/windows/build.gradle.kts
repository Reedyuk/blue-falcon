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
    
    jvm()
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
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
        artifactId = "blue-falcon-engine-windows",
        version = "3.0.0-alpha01"
    )
}
