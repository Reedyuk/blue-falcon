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

kotlin {
    jvmToolchain(17)
    
    // macOS targets
    macosArm64 { bluefalconL2capInterop() }
    macosX64 { bluefalconL2capInterop() }
    
    sourceSets {
        val macosMain by creating {
            kotlin.srcDir("../apple-shared/src/appleMain/kotlin")
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }

        val macosTest by creating {
            dependsOn(commonTest.get())
            kotlin.srcDir("../apple-shared/src/appleTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(
                    "org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinx_coroutines_version"
                )
            }
        }
        
        val macosArm64Main by getting {
            dependsOn(macosMain)
        }

        val macosArm64Test by getting {
            dependsOn(macosTest)
        }
        
        val macosX64Main by getting {
            dependsOn(macosMain)
        }

        val macosX64Test by getting {
            dependsOn(macosTest)
        }
    }
}

/**
 * Objective-C helper that owns CBL2CAPChannel objects outside Kotlin (#334). The `.m` file is
 * compiled per target into a static library, which cinterop embeds in the klib.
 */
fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.bluefalconL2capInterop() {
    val (sdk, arch, minVersion) = when (konanTarget) {
        org.jetbrains.kotlin.konan.target.KonanTarget.IOS_ARM64 -> Triple("iphoneos", "arm64", "-miphoneos-version-min=12.0")
        org.jetbrains.kotlin.konan.target.KonanTarget.IOS_SIMULATOR_ARM64 -> Triple("iphonesimulator", "arm64", "-mios-simulator-version-min=12.0")
        org.jetbrains.kotlin.konan.target.KonanTarget.IOS_X64 -> Triple("iphonesimulator", "x86_64", "-mios-simulator-version-min=12.0")
        org.jetbrains.kotlin.konan.target.KonanTarget.MACOS_ARM64 -> Triple("macosx", "arm64", "-mmacosx-version-min=10.15")
        org.jetbrains.kotlin.konan.target.KonanTarget.MACOS_X64 -> Triple("macosx", "x86_64", "-mmacosx-version-min=10.15")
        else -> error("bfl2cap: unsupported target $konanTarget")
    }
    val source = project.file("../apple-shared/src/nativeInterop/objc")
    val outDir = project.layout.buildDirectory.dir("bfl2cap/$name").get().asFile
    val compile = project.tasks.register("compileBfl2cap" + name.replaceFirstChar { it.uppercase() }, Exec::class.java) {
        inputs.dir(source)
        outputs.dir(outDir)
        doFirst { outDir.mkdirs() }
        commandLine(
            "sh", "-c",
            "xcrun --sdk $sdk clang -c -fobjc-arc -fmodules -Os -arch $arch $minVersion " +
                "-I'$source' '$source/bfl2cap.m' -o '$outDir/bfl2cap.o' && " +
                "xcrun libtool -static -o '$outDir/libbfl2cap.a' '$outDir/bfl2cap.o'",
        )
    }
    compilations.getByName("main").cinterops.create("bfl2cap") {
        definitionFile.set(project.file("../apple-shared/src/nativeInterop/cinterop/bfl2cap.def"))
        packageName("dev.bluefalcon.engine.apple.objc")
        includeDirs(source)
        extraOpts("-libraryPath", outDir.absolutePath)
        project.tasks.named(interopProcessingTaskName).configure { dependsOn(compile) }
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
