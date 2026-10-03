// SPDX-License-Identifier: AGPL-3.0-or-later
// The Google Voice connector (BUILD_PLAN.md Phase 6, network 4) over the Go bridge (P3.1),
// which binds libgv (from mautrix-gvoice) with gomobile.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.pingme.connectors.gvoice"

    // The connector logs what Google Voice sends (android.util.Log); unit tests have no Android.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(project(":core:connector-api"))
    implementation(project(":gobridge"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:connector-contract"))
}
