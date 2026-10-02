// SPDX-License-Identifier: AGPL-3.0-or-later
// The Google Messages connector (BUILD_PLAN.md P3.2) over the Go bridge (P3.1), the
// :gobridge module, which builds libgm with gomobile on demand.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.pingme.connectors.gmessages"

    // The connector logs what the phone sends (android.util.Log); unit tests have no Android.
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
