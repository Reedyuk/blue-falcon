plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
//    "signing" apply false
}

group = "dev.bluefalcon"
version = libs.versions.blue.falcon.get()

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishAllLibraryVariants()
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(libs.coroutines)
                implementation(project(":blue-falcon-core"))
            }
        }
    }
}

android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    namespace = "dev.bluefalcon.engine"
    defaultConfig {
        minSdk = 24
        targetSdk = 33
    }
    lint {
        disable += "MissingPermission"
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
