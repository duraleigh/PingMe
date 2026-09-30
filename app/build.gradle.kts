// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.pingme.app"

    defaultConfig {
        applicationId = "org.pingme.app"
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
}

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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
