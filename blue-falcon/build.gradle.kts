import org.gradle.kotlin.dsl.support.kotlinCompilerOptions
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension

plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
//    "signing" apply false
}

subprojects {
    // Apply the Vanniktech Maven Publish plugin to all subprojects
    apply(plugin = "com.vanniktech.maven.publish")

    afterEvaluate {
        extensions.configure<PublishingExtension> {
            repositories {
                mavenLocal()
//                maven {
//                    name = "githubPackages"
//                    url = uri("https://maven.pkg.github.com/reedyuk/blue-falcon")
//                    credentials {
//                        username = System.getenv("GITHUB_PACKAGES_USERNAME")
//                        password = System.getenv("GITHUB_PACKAGES_PASSWORD")
//                    }
//                }
            }
        }
    }

    afterEvaluate {
        extensions.findByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java)?.apply {
            @OptIn(ExperimentalKotlinGradlePluginApi::class)
            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }
}
