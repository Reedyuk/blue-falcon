import java.util.*
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
//    "signing" apply false
}

group = "dev.bluefalcon"
version = libs.versions.blue.falcon.get()

kotlin {
    js {
        browser {
            binaries.executable()
        }
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(libs.coroutines)
                implementation(project(":blue-falcon-core"))
            }
        }
    }
}
