import java.util.*

buildscript {
    val kotlin_version: String by project
    val android_tools_version: String by project

    repositories {
        mavenLocal()
        jcenter()
        google()
        maven(url = "https://dl.bintray.com/jetbrains/kotlin-native-dependencies")
        maven(url = "https://maven.google.com")
        maven(url = "https://plugins.gradle.org/m2/")
        maven(url = "https://dl.bintray.com/kotlin/kotlin-eap")
        maven(url = "https://kotlin.bintray.com/kotlinx")
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version")
        classpath("com.android.tools.build:gradle:$android_tools_version")
        classpath("com.jfrog.bintray.gradle:gradle-bintray-plugin:1.8.4")
    }
}

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.native.cocoapods")
    id("maven-publish")
    id("signing")
}

val sonatypeStaging = "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
val sonatypeSnapshots = "https://oss.sonatype.org/content/repositories/snapshots/"

val local = Properties()
val localProperties: File = rootProject.file("local.properties")
if (localProperties.exists()) {
    localProperties.inputStream().use { local.load(it) }
}

val sonatypePasswordEnv = local.getProperty("sonatypePasswordEnv")
val sonatypeUsernameEnv = local.getProperty("sonatypeUsernameEnv")

repositories {
    mavenLocal()
    mavenCentral()
    google()
    jcenter()
    maven(url = "https://kotlin.bintray.com/kotlinx")
    maven(url = "https://dl.bintray.com/kotlin/kotlin-dev")
    maven(url = "https://dl.bintray.com/kotlin/kotlin-eap")
}

configurations.create("compileClasspath")

kotlin {
    cocoapods {
        // Configure fields required by CocoaPods.
        summary = "Blue-Falcon a multiplatform bluetooth library"
        homepage = "http://www.bluefalcon.dev"
    }

    //need to use jvm because android doesnt export type alias
    jvm("android") {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }

    js {
        val main by compilations.getting {
            kotlinOptions {
                metaInfo = true
                sourceMap = true
                sourceMapEmbedSources = "always"
                moduleKind = "commonjs"
            }
        }
    }

    val buildForDevice = project.findProperty("kotlin.native.cocoapods.target") == "ios_arm"
    val iosMain by sourceSets.creating
    if (buildForDevice) {
        iosArm64("ios64")
        sourceSets["ios64Main"].dependsOn(iosMain)
    } else {
        iosX64("ios")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib-common")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib")
                compileOnly("org.robolectric:android-all:9-robolectric-4913185-2")
            }
        }

        val androidTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("com.android.support.test:runner:1.0.2")
            }
        }

        val iosMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-native:1.3.0-M2")
            }
        }

        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib-js")
                implementation("org.jetbrains.kotlin:kotlin-stdlib")
            }
        }

        //JS tests currently not working, need to wait for jetbrains to release support
        val jsTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test-js")
            }
        }
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
            name.set("dev.bluefalcon")
            description.set("Kotlin Multiplatform Bluetooth Library")
            url.set("https://github.com/reedyuk/blue-falcon")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("http://opensource.org/licenses/MIT")
                }
            }

            developers {
                developer {
                    id.set("Reedyuk")
                    name.set("Andrew Reed")
                    email.set("andrew_reed@hotmail.com")
                }
            }

            scm {
                url.set("https://github.com/reedyuk/blue-falcon")
                connection.set("scm:git:git://git@github.com:reedyuk/blue-falcon.git")
                developerConnection.set("scm:git:ssh://git@github.com:reedyuk/blue-falcon.git")
            }

        }
    }

}

signing {
    whenRequired { gradle.taskGraph.hasTask("publish") }
    sign(publishing.publications)
}
