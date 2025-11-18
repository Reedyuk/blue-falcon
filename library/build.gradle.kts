import java.util.*
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.0.20"
    id("com.android.library")
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

repositories {
    google()
    mavenCentral()
    google()
    maven("https://jitpack.io")
    mavenLocal()
}

val local = Properties()
val localProperties: File = rootProject.file("local.properties")
if (localProperties.exists()) {
    localProperties.inputStream().use { local.load(it) }
}
val projectGithubUrl: String by project
val projectGithubSCM: String by project
val projectGithubSCMSSL: String by project
val projectDescription: String by project

val developerId: String by project
val developerName: String by project
val developerEmail: String by project
val group: String by project
val libraryName: String by project
val version: String by project

val kotlinx_coroutines_version: String by project

android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    namespace = "dev.bluefalcon.blueFalcon"
    defaultConfig {
        minSdk = 24
        targetSdk = 33
    }
    lint {
        disable += "MissingPermission"
    }
}

val frameworkName = "BlueFalcon"

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishAllLibraryVariants()
    }
//    jvm("rpi") {
//        compilations.all {
//            kotlinOptions.jvmTarget = "1.8"
//        }
//    }
    js {
        browser {
            binaries.executable()
        }
    }
    iosSimulatorArm64()
    iosX64()
    iosArm64()
    macosArm64()
    macosX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val androidMain by getting
//        val rpiMain by getting {
//            dependencies {
//                implementation("com.github.weliem:blessed-bluez:0.38")
//            }
//        }
        val jsMain by getting
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group,
        artifactId = libraryName,
        version = version
    )

    pom {
        name.set(group)
        description.set(projectDescription)
        url.set(projectGithubUrl)

        licenses {
            license {
                name.set("MIT License")
                url.set("http://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set(developerId)
                name.set(developerName)
                email.set(developerEmail)
            }
        }

        scm {
            url.set(projectGithubUrl)
            connection.set(projectGithubSCM)
            developerConnection.set(projectGithubSCMSSL)
        }
    }
}

val javadocJar by tasks.creating(Jar::class) {
    archiveClassifier.value("javadoc")
}

signing {
    setRequired {
        !gradle.taskGraph.allTasks.any { it is PublishToMavenLocal }
    }
    val key = local.getProperty("signingKey") ?: System.getenv("SIGNING_KEY")
    val password = local.getProperty("signingPassword") ?: System.getenv("SIGNING_PASSWORD")
    if (key != null && password != null) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications) // This ensures all created publications are signed
    } else {
        // Optional: Log a warning if keys are missing for a release build
        logger.warn("Signing key or password not found. Publication will not be signed.")
    }
}
