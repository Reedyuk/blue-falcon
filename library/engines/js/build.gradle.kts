plugins {
    kotlin("multiplatform") version "2.3.0"
    id("com.vanniktech.maven.publish")
    id("signing")
}

repositories {
    google()
    mavenCentral()
}

val kotlinx_coroutines_version: String by project
val kotlinx_browser_version: String by project
val versionEngines: String by project

kotlin {
    jvmToolchain(17)

    // The shared Web Bluetooth interop is modelled with expect/actual classes.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Both browser backends share the same Web Bluetooth logic (see webMain below).
    js(IR) {
        browser()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        val commonMain by getting

        // Intermediate source set holding the shared engine orchestration. The
        // platform-specific Web Bluetooth interop lives in jsMain / wasmJsMain
        // behind the expect/actual surface declared here.
        val webMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }

        val jsMain by getting {
            dependsOn(webMain)
        }
        val wasmJsMain by getting {
            dependsOn(webMain)
            dependencies {
                // org.w3c.dom / org.khronos.webgl ship in the JS stdlib but must be
                // pulled in explicitly for the wasmJs target.
                implementation("org.jetbrains.kotlinx:kotlinx-browser:$kotlinx_browser_version")
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
        artifactId = "blue-falcon-engine-js",
        version = versionEngines
    )
    
    pom {
        name.set("Blue Falcon JavaScript Engine")
        description.set("JavaScript BLE engine for Blue Falcon")
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
