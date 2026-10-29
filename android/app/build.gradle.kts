plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }

fun getBuildProperty(name: String): String {
    val value =
        project.findProperty(name)?.toString()
            ?: localProperties.getProperty(name)
            ?: ""
    return value.trim().replace("\"", "\\\"")
}

android {
    namespace = "com.metalens.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.metalens.app"
        minSdk = 31
        targetSdk = 34
        versionCode = 13
        versionName = "0.13.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Prototype only: set -POPENAI_API_KEY / -POPENAI_MODEL, or define them in:
        // - ~/.gradle/gradle.properties
        // - android/local.properties (NOT committed)
        // (Do NOT commit API keys.)
        buildConfigField(
            "String",
            "OPENAI_API_KEY",
            "\"${getBuildProperty("OPENAI_API_KEY")}\"",
        )
        buildConfigField(
            "String",
            "OPENAI_MODEL",
            "\"${getBuildProperty("OPENAI_MODEL")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
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

    lint {
        // Several third-party lint checks (e.g. androidx.lifecycle 2.8.7's
        // NonNullableMutableLiveDataDetector) crash under Kotlin 2.1.20 with
        // IncompatibleClassChangeError on KaCallableMemberCall. Skip lint for
        // release APK builds entirely — this is an unsigned sideload release,
        // not a Play Store upload, so lintVital isn't worth blocking on.
        checkReleaseBuilds = false
        abortOnError = false
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.androidx.exifinterface)

    implementation(libs.okhttp)
}
