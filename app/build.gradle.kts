// SPDX-License-Identifier: AGPL-3.0-or-later
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// The GIPHY key for GIF search (UI_DESIGN.md 5.5) is never committed: CI passes the
// GIPHY_API_KEY secret, and local builds read giphy.apiKey from local.properties. Without
// one, GIF search is off and the picker offers favourites only.
val localProperties =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }
val giphyKey =
    providers.environmentVariable("GIPHY_API_KEY").orNull?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("giphy.apiKey").orEmpty()

android {
    namespace = "org.pingme.app"

    defaultConfig {
        applicationId = "org.pingme.app"
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIPHY_API_KEY", "\"$giphyKey\"")
    }

    // Sideload signing (BUILD_PLAN.md P0.3). CI decodes the SIDELOAD_KEYSTORE_B64
    // secret to a file and passes its path in SIDELOAD_KEYSTORE_FILE, with the other
    // three secrets as they are. Without them (local builds) release falls back to
    // the debug key. A half-configured CI fails instead, because an APK signed with
    // the wrong key will not install over the owner's copy.
    val sideloadKeystore = providers.environmentVariable("SIDELOAD_KEYSTORE_FILE").orNull
    signingConfigs {
        if (sideloadKeystore != null) {
            create("sideload") {
                storeFile = file(sideloadKeystore)
                storePassword = requiredEnv("SIDELOAD_KEYSTORE_PASSWORD")
                keyAlias = requiredEnv("SIDELOAD_KEY_ALIAS")
                keyPassword = requiredEnv("SIDELOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (sideloadKeystore != null) "sideload" else "debug")
        }
        // The demo build (BUILD_PLAN.md P2.8, Gate G1): the demo network, installed beside the
        // real app as "PingMe Demo", and signed with the sideload key on CI so it updates in place.
        debug {
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            if (sideloadKeystore != null) signingConfig = signingConfigs.getByName("sideload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

fun requiredEnv(name: String): String =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("SIDELOAD_KEYSTORE_FILE is set but $name is missing or empty")

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:store"))
    implementation(project(":core:connector-api"))
    implementation(project(":core:service"))
    implementation(project(":core:ui"))

    implementation(project(":connectors:gmessages"))
    implementation(project(":connectors:sms"))
    implementation(project(":connectors:whatsapp"))
    implementation(project(":connectors:telegram"))
    implementation(project(":connectors:signal"))
    implementation(project(":connectors:gvoice"))
    implementation(project(":connectors:instagram"))
    implementation(project(":connectors:messenger"))
    implementation(project(":connectors:fbpage"))
    // The demo network ships in debug builds only (BUILD_PLAN.md P1.5).
    debugImplementation(project(":connectors:demo"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.materialkolor.utilities)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.zxing.core)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
