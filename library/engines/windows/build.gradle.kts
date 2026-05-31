import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.gradle.process.ExecOperations
import javax.inject.Inject
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity


plugins {
    kotlin("multiplatform") version "2.3.0"
    id("com.vanniktech.maven.publish")
    id("signing")
}

@CacheableTask
abstract class CompileNativeWindowsTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeSrcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val cmakeBuildDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val srcDir = nativeSrcDir.get().asFile
        val destDir = outputDir.get().asFile
        val buildDir = cmakeBuildDir.get().asFile

        buildDir.mkdirs()
        destDir.mkdirs()

        val configureResult = execOperations.exec {
            workingDir = buildDir
            commandLine = listOf("cmake", srcDir.absolutePath, "-A", "x64")
            isIgnoreExitValue = true
        }
        if (configureResult.exitValue != 0) {
            throw GradleException("CMake configure failed with exit code ${configureResult.exitValue}")
        }

        val buildResult = execOperations.exec {
            workingDir = buildDir
            commandLine = listOf("cmake", "--build", ".", "--config", "Release", "--parallel")
            isIgnoreExitValue = true
        }
        if (buildResult.exitValue != 0) {
            throw GradleException("CMake build failed with exit code ${buildResult.exitValue}")
        }

        val dllFile = File(buildDir, "Release/bluefalcon-windows.dll")
        if (dllFile.exists()) {
            dllFile.copyTo(File(destDir, "bluefalcon-windows.dll"), overwrite = true)
        } else {
            throw GradleException("Failed to find compiled DLL at ${dllFile.absolutePath}")
        }
    }
}

repositories {
    google()
    mavenCentral()
}

val kotlinx_coroutines_version: String by project
val versionEngines: String by project

val isWindows = DefaultNativePlatform.getCurrentOperatingSystem().isWindows
val generatedResourcesDir = layout.buildDirectory.dir("generated-resources")

val compileNativeWindows by tasks.registering(CompileNativeWindowsTask::class) {
    enabled = isWindows
    nativeSrcDir.set(project(":").layout.projectDirectory.dir("src/windowsMain/cpp"))
    cmakeBuildDir.set(layout.buildDirectory.dir("cmake-build"))
    outputDir.set(generatedResourcesDir.map { it.dir("natives") })
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
