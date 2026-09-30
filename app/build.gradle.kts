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
    }

    buildFeatures {
        compose = true
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
