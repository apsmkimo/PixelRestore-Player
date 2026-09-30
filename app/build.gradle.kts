import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pixelrestore.player"
    // SMCPKG_SUPPORT>>>Cursor011
    // compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    // SMCPKG_SUPPORT<<<Cursor012

    defaultConfig {
        applicationId = "com.pixelrestore.player"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        // SMCPKG_SUPPORT>>>Cursor085
        // ONNX Runtime ships a .so per ABI. Phone + 64-bit emulator only;
        // the model file itself stays about 120 KB.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        // SMCPKG_SUPPORT<<<Cursor086
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // SMCPKG_SUPPORT>>>Cursor079
    androidResources {
        noCompress += "onnx"
    }
    // SMCPKG_SUPPORT<<<Cursor080

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    // SMCPKG_SUPPORT>>>Cursor081
    implementation(libs.onnxruntime.android)
    // SMCPKG_SUPPORT<<<Cursor082

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
