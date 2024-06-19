plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(11)
}

android {
    namespace = "dev.bluefalcon.kotlinmp_example.android"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.bluefalcon.kotlinmp_example.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

composeCompiler {
    enableStrongSkippingMode = true
}

dependencies {
    implementation(projects.shared)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.blue.falcon)
    implementation(libs.androidx.appcompat)
    implementation(libs.compose.permissions)
    debugImplementation(libs.compose.ui.tooling)
}