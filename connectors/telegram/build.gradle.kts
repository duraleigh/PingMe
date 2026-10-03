// SPDX-License-Identifier: AGPL-3.0-or-later
// The Telegram connector (BUILD_PLAN.md Phase 6, network 2) over TDLib, taken prebuilt
// from Maven Central (io.github.tdlib-android:core, TDLib 1.8.67, all four chips) as the
// plan allows, instead of an hours-long native build.
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Telegram's app credentials (api_id, api_hash from my.telegram.org) are never committed:
// CI passes the TELEGRAM_API_ID and TELEGRAM_API_HASH secrets, and local builds read
// telegram.apiId and telegram.apiHash from local.properties. Without them signing in fails
// with a plain message.
val localProperties =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }
val telegramApiId =
    providers.environmentVariable("TELEGRAM_API_ID").orNull?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("telegram.apiId").orEmpty()
val telegramApiHash =
    providers.environmentVariable("TELEGRAM_API_HASH").orNull?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("telegram.apiHash").orEmpty()

android {
    namespace = "org.pingme.connectors.telegram"

    defaultConfig {
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toIntOrNull()?.toString() ?: "0")
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // The connector logs what Telegram sends (android.util.Log); unit tests have no Android.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(project(":core:connector-api"))
    implementation(libs.tdlib.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:connector-contract"))
}
