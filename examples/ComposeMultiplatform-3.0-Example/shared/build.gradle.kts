plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val falconVersion = "3.4.0"
val coroutinesVersion = "1.11.0"

kotlin {
    jvmToolchain(21)
    androidTarget()

    // JVM desktop (Windows + Linux)
    jvm()

    // macOS native
    macosArm64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
    macosX64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }

    targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java).all {
        binaries.withType(org.jetbrains.kotlin.gradle.plugin.mpp.Framework::class.java).all {
            export("dev.icerock.moko:mvvm-core:0.16.1")
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)

                // Coroutines (must be explicit — blue-falcon-core uses implementation, not api)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

                // Use api() for mvvm-core since it's exported in iOS framework
                api("dev.icerock.moko:mvvm-core:0.16.1")
                implementation("dev.icerock.moko:mvvm-compose:0.16.1")
                implementation("dev.icerock.moko:mvvm-flow:0.16.1")
                implementation("dev.icerock.moko:mvvm-flow-compose:0.16.1")

                // Blue Falcon 3.0
                implementation("dev.bluefalcon:blue-falcon-core:$falconVersion")
                implementation("dev.bluefalcon:blue-falcon-plugin-logging:$falconVersion")
                implementation("dev.bluefalcon:blue-falcon-plugin-retry:$falconVersion")
                implementation("dev.bluefalcon:blue-falcon-plugin-nordic-fota:$falconVersion")
                implementation("dev.bluefalcon:blue-falcon-plugin-clone:$falconVersion")
                implementation("dev.bluefalcon:blue-falcon-plugin-broadcast:$falconVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("androidx.activity:activity-compose:1.7.2")

                // Blue Falcon Android Engine
                implementation("dev.bluefalcon:blue-falcon-engine-android:$falconVersion")
            }
        }
        val androidUnitTest by getting
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependencies {
                // Blue Falcon iOS Engine
                implementation("dev.bluefalcon:blue-falcon-engine-ios:$falconVersion")
            }
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }
        val iosX64Test by getting
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        val iosTest by creating {
            dependsOn(commonTest)
            iosX64Test.dependsOn(this)
            iosArm64Test.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
        }

        // JVM desktop (Windows, Linux, macOS via JNI)
        val jvmMain by getting {
            dependencies {
                // Blue Falcon Windows Engine (JNI + WinRT, publish engines locally first)
                implementation("dev.bluefalcon:blue-falcon-engine-windows:$falconVersion")
                // Blue Falcon Linux Engine (BlueZ via Blessed)
                implementation("dev.bluefalcon:blue-falcon-engine-rpi:$falconVersion")
                // Blue Falcon macOS JVM Engine (JNI + CoreBluetooth, publish engines locally first)
                implementation("dev.bluefalcon:blue-falcon-engine-macos-jvm:$falconVersion")
            }
        }

        // macOS native (CoreBluetooth via Kotlin/Native cinterop)
        val macosArm64Main by getting
        val macosX64Main by getting
        val macosMain by creating {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon-engine-macos:$falconVersion")
            }
            dependsOn(commonMain)
            macosArm64Main.dependsOn(this)
            macosX64Main.dependsOn(this)
        }
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}

android {
    namespace = "com.plcoding.contactscomposemultiplatform"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
