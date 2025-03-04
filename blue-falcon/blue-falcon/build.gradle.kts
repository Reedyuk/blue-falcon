import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "dev.bluefalcon"
version = libs.versions.blue.falcon.get()

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
                api(project(":blue-falcon-core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(project(":blue-falcon-engine-android"))
            }
        }
        val darwinMain by creating {
            dependencies {
                dependsOn(commonMain)
                implementation(project(":blue-falcon-engine-darwin"))
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

        val jsMain by getting {
            dependencies {
                implementation(project(":blue-falcon-engine-js"))
            }
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
