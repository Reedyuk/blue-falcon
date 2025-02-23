import java.util.*
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
//    "signing" apply false
}

group = "dev.bluefalcon-darwin"
version = libs.versions.blue.falcon.get()


//plugins {
//    kotlin("multiplatform") version "2.0.20"
////    id("com.android.library")
//    id("maven-publish")
//    id("signing")
//}

//repositories {
//    google()
//    mavenCentral()
//    google()
//    maven("https://jitpack.io")
//    mavenLocal()
//}
//
////expose properties
//val sonatypeStaging = "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
//val sonatypeSnapshots = "https://oss.sonatype.org/content/repositories/snapshots/"
//
//val local = Properties()
//val localProperties: File = rootProject.file("local.properties")
//if (localProperties.exists()) {
//    localProperties.inputStream().use { local.load(it) }
//}
//
//val sonatypePasswordEnv = System.getenv("sonatypePasswordEnv")
//val sonatypeUsernameEnv = System.getenv("sonatypeUsernameEnv")
//
//val projectGithubUrl: String by project
//val projectGithubSCM: String by project
//val projectGithubSCMSSL: String by project
//val projectDescription: String by project
//
//val developerId: String by project
//val developerName: String by project
//val developerEmail: String by project
//val group: String by project
//
//val kotlinx_coroutines_version: String by project
//
//android {
//    compileSdk = 33
//    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
//    namespace = "dev.bluefalcon.blueFalcon"
//    defaultConfig {
//        minSdk = 24
//        targetSdk = 33
//    }
//    lint {
//        disable += "MissingPermission"
//    }
//}

val frameworkName = "BlueFalcon"

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

kotlin {
    iosSimulatorArm64()
    iosX64()
    iosArm64()
    macosArm64()
    macosX64()

    sourceSets {
        val commonMain by getting { }
        val darwinMain by creating {
            dependencies {
                dependsOn(commonMain)
                implementation(libs.coroutines)
                implementation(project(":blue-falcon-core"))
            }
        }
        val macosX64Main by getting {
            dependsOn(darwinMain)
        }
        val iosX64Main by getting {
            dependsOn(darwinMain)
        }
        val macosArm64Main by getting {
            dependsOn(darwinMain)
        }
        val iosArm64Main by getting {
            dependsOn(darwinMain)
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(darwinMain)
        }
    }
}

//fun SigningExtension.whenRequired(block: () -> Boolean) {
//    setRequired(block)
//}
//
//val javadocJar by tasks.creating(Jar::class) {
//    archiveClassifier.value("javadoc")
//}
//
//publishing {
//    repositories {
//        maven {
//            url = uri(sonatypeStaging)
//
//            credentials {
//                username = sonatypeUsernameEnv
//                password = sonatypePasswordEnv
//            }
//        }
//    }
//
//    publications.all {
//        this as MavenPublication
//
//        println(name)
//        artifact(javadocJar)
//
//        pom {
//            name.set(group)
//            description.set(projectDescription)
//            url.set(projectGithubUrl)
//
//            licenses {
//                license {
//                    name.set("MIT License")
//                    url.set("http://opensource.org/licenses/MIT")
//                }
//            }
//
//            developers {
//                developer {
//                    id.set(developerId)
//                    name.set(developerName)
//                    email.set(developerEmail)
//                }
//            }
//
//            scm {
//                url.set(projectGithubUrl)
//                connection.set(projectGithubSCM)
//                developerConnection.set(projectGithubSCMSSL)
//            }
//
//        }
//    }
//
//}
//
//afterEvaluate {
//    signing {
//        whenRequired { gradle.taskGraph.hasTask("publish") }
//        val signingKey: String? by project
//        val signingPassword: String? by project
//        useInMemoryPgpKeys(signingKey, signingPassword)
//        sign(publishing.publications)
//    }
//}
//
//tasks.withType<AbstractPublishToMaven>().configureEach {
//    val signingTasks = tasks.withType<Sign>()
//    mustRunAfter(signingTasks)
//}
//
