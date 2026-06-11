import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.3.0"
}

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

// Must match the version published to mavenLocal from ../../library.
val falconVersion = "3.4.3"

kotlin {
    jvmToolchain(17)

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        // Fixed module name so index.html can reference the bundle by a stable path.
        outputModuleName.set("blueFalconWasmExample")
        browser {
            commonWebpackConfig {
                outputFileName = "blueFalconWasmExample.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon-core:$falconVersion")
                // The js engine artifact ships both the js and wasmJs browser variants;
                // Gradle resolves the wasmJs klib for this target automatically.
                implementation("dev.bluefalcon:blue-falcon-engine-js:$falconVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
            }
        }
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}