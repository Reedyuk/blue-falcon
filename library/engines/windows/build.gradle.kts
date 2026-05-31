import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

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
val versionEngines: String by project

val isWindows = DefaultNativePlatform.getCurrentOperatingSystem().isWindows
val generatedResourcesDir = layout.buildDirectory.dir("generated-resources")

val compileNativeWindows by tasks.registering {
    enabled = isWindows

    val nativeSrc = project(":").file("src/windowsMain/cpp")
    val cmakeBuild = file("${layout.buildDirectory.get().asFile}/cmake-build")
    val output = file("${generatedResourcesDir.get().asFile}/natives")

    inputs.dir(nativeSrc)
    outputs.dir(output)

    doLast {
        val srcDir = nativeSrc
        val buildDir = cmakeBuild
        val destDir = output

        buildDir.mkdirs()
        destDir.mkdirs()

        val configureExit = ProcessBuilder("cmake", srcDir.absolutePath, "-A", "x64")
            .directory(buildDir)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
        if (configureExit != 0) {
            throw GradleException("CMake configure failed with exit code $configureExit")
        }

        val buildExit = ProcessBuilder("cmake", "--build", ".", "--config", "Release")
            .directory(buildDir)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
        if (buildExit != 0) {
            throw GradleException("CMake build failed with exit code $buildExit")
        }

        val dllFile = File(buildDir, "Release/bluefalcon-windows.dll")
        if (dllFile.exists()) {
            dllFile.copyTo(File(destDir, "bluefalcon-windows.dll"), overwrite = true)
        } else {
            throw GradleException("Failed to find compiled DLL at ${dllFile.absolutePath}")
        }
    }
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
    if (isWindows) {
        dependsOn(compileNativeWindows)
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
        artifactId = "blue-falcon-engine-windows",
        version = versionEngines
    )
    
    pom {
        name.set("Blue Falcon Windows Engine")
        description.set("Windows BLE engine for Blue Falcon")
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
