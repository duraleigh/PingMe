// SPDX-License-Identifier: AGPL-3.0-or-later
// The Facebook Page inbox connector (BUILD_PLAN.md Phase 6, network 7): Meta's official
// Messenger Platform (Graph API) polled from the phone with a Page access token.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.pingme.connectors.fbpage"

    // The connector logs what the Page API sends (android.util.Log); unit tests have no Android.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(project(":core:connector-api"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:connector-contract"))
}
