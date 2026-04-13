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
val versionEngines: String by project

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
version = versionEngines

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "dev.bluefalcon",
        artifactId = "blue-falcon-engine-macos",
        version = versionEngines
    )
    
    pom {
        name.set("Blue Falcon macOS Engine")
        description.set("macOS BLE engine for Blue Falcon")
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
