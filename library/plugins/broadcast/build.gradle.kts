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
val versionPlugins: String by project

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
                api(project(":core"))
                api(project(":plugins:clone"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinx_coroutines_version")
            }
        }
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

// Publishing configuration
group = "dev.bluefalcon"
version = versionPlugins

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "dev.bluefalcon",
        artifactId = "blue-falcon-plugin-broadcast",
        version = versionPlugins
    )

    pom {
        name.set("Blue Falcon Broadcast Plugin")
        description.set("BLE device broadcast plugin for Blue Falcon - Re-advertise a cloned device profile as a local BLE peripheral")
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
    setRequired {
        gradle.taskGraph.allTasks.any {
            it.name.contains("publishTo") && it.name.contains("MavenCentral")
        }
    }
}
