import java.util.*

plugins {
    kotlin("multiplatform") version "1.6.0"
    id("com.android.library")
    id("maven-publish")
    id("signing")
}

repositories {
    google()
    mavenCentral()
    google()
    maven("https://jitpack.io")
    mavenLocal()
}

//expose properties
val sonatypeStaging = "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
val sonatypeSnapshots = "https://oss.sonatype.org/content/repositories/snapshots/"

val local = Properties()
val localProperties: File = rootProject.file("local.properties")
if (localProperties.exists()) {
    localProperties.inputStream().use { local.load(it) }
}

val sonatypePasswordEnv = System.getenv("sonatypePasswordEnv")
val sonatypeUsernameEnv = System.getenv("sonatypeUsernameEnv")

val projectGithubUrl: String by project
val projectGithubSCM: String by project
val projectGithubSCMSSL: String by project
val projectDescription: String by project

val developerId: String by project
val developerName: String by project
val developerEmail: String by project
val group: String by project

android {
    compileSdk = 29
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 24
        targetSdk = 29
    }
}

kotlin {
    android {
        publishLibraryVariants("debug", "release")
    }
    jvm("rpi") {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
    js {
        browser {
            webpackTask {
                output.libraryTarget = "umd"
            }
            binaries.executable()
        }
    }
    iosSimulatorArm64 {
        binaries.framework("BlueFalcon")
    }
    iosArm64("ios") {
        binaries.framework("BlueFalcon")
    }
    macosX64 {
        binaries.framework("BlueFalcon")
    }

    sourceSets {
        val commonMain by getting {}
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val androidMain by getting {
            dependencies {
            }
        }
        val rpiMain by getting {
            dependencies {
                implementation("com.github.weliem:blessed-bluez:0.38")
            }
        }
        val jsMain by getting
        val iosMain by getting
        val iosSimulatorArm64Main by getting
        iosSimulatorArm64Main.dependsOn(iosMain)
        val macosX64Main by getting
    }
}

fun SigningExtension.whenRequired(block: () -> Boolean) {
    setRequired(block)
}

val javadocJar by tasks.creating(Jar::class) {
    archiveClassifier.value("javadoc")
}

publishing {
    repositories {
        maven {
            url = uri(sonatypeStaging)

            credentials {
                username = sonatypeUsernameEnv
                password = sonatypePasswordEnv
            }
        }
    }

    publications.all {
        this as MavenPublication

        println(name)
        artifact(javadocJar)

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

}

signing {
    whenRequired { gradle.taskGraph.hasTask("publish") }
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications)
}

kotlin {
    tasks {
        register("copyIosFrameworkIntoUniversalFramework", Copy::class) {
            from(file("$buildDir/bin/ios/BlueFalconReleaseFramework/BlueFalcon.framework"))
            into(file("$buildDir/universal/BlueFalcon.xcframework/ios-arm64/BlueFalcon.framework"))
        }
        register("copySimulatorFrameworkIntoUniversalFramework", Copy::class) {
            from(file("$buildDir/bin/iosSimulatorArm64/BlueFalconReleaseFramework/BlueFalcon.framework"))
            into(file("$buildDir/universal/BlueFalcon.xcframework/ios-arm64_x86_64-simulator/BlueFalcon.framework"))
        }
        register("copyMacosFrameworkIntoUniversalFramework", Copy::class) {
            from(file("$buildDir/bin/macosX64/BlueFalconReleaseFramework/BlueFalcon.framework"))
            into(file("$buildDir/universal/BlueFalcon.xcframework/macos-arm64_x86_64/BlueFalcon.framework"))
        }
        register("copyPlistIntoUniversalFramework", Copy::class) {
            from(file("${project.projectDir}/Info.plist"))
            into(file("$buildDir/universal/BlueFalcon.xcframework"))
        }
        register("copyFrameworksIntoUniversalFramework") {
            mustRunAfter("linkBlueFalconIos", "linkBlueFalconIosSimulatorArm64", "copyMacosFrameworkIntoUniversalFramework")
            mustRunAfter("linkBlueFalconIos")
            dependsOn("copyIosFrameworkIntoUniversalFramework", "copySimulatorFrameworkIntoUniversalFramework", "copyPlistIntoUniversalFramework", "copyMacosFrameworkIntoUniversalFramework")
        }
        register("makeUniversalFramework") {
            dependsOn("linkBlueFalconIos")
            dependsOn("linkBlueFalconIosSimulatorArm64")
            dependsOn("linkBlueFalconMacosX64")
            dependsOn("copyFrameworksIntoUniversalFramework")
        }
    }
}
