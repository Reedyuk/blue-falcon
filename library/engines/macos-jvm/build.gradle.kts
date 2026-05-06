import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

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

val isMacOs = DefaultNativePlatform.getCurrentOperatingSystem().isMacOsX

val generatedResourcesDir = layout.buildDirectory.dir("generated-resources")

val compileNativeMacos by tasks.registering(Exec::class) {
    enabled = isMacOs

    val nativeSrcDir = file("native")
    val outputDir = generatedResourcesDir.get().dir("natives").asFile

    inputs.dir(nativeSrcDir)
    outputs.dir(outputDir)

    val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home") ?: ""

    commandLine(
        "clang",
        "-dynamiclib",
        "-framework", "CoreBluetooth",
        "-framework", "Foundation",
        "-I", "$javaHome/include",
        "-I", "$javaHome/include/darwin",
        "$nativeSrcDir/BlueFalconJNI.m",
        "-o", "${outputDir.absolutePath}/libbluefalcon-macos.dylib"
    )
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        val jvmMain by getting {
            resources.srcDir(generatedResourcesDir)
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }
    }
}

tasks.named("jvmProcessResources") {
    dependsOn(compileNativeMacos)
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

group = "dev.bluefalcon"
version = versionEngines

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "dev.bluefalcon",
        artifactId = "blue-falcon-engine-macos-jvm",
        version = versionEngines
    )

    pom {
        name.set("Blue Falcon macOS JVM Engine")
        description.set("macOS BLE engine for Blue Falcon — JVM/Compose Desktop via JNI + CoreBluetooth")
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

signing {
    setRequired {
        gradle.taskGraph.allTasks.any {
            it.name.contains("publishTo") && it.name.contains("MavenCentral")
        }
    }
}
