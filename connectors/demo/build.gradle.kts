// SPDX-License-Identifier: AGPL-3.0-or-later
// The demo network (BUILD_PLAN.md P1.5): a fake network for UI tests, screenshots, and
// trying PingMe without accounts. The app includes it in debug builds only.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.pingme.connectors.demo"
}

dependencies {
    api(project(":core:connector-api"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:connector-contract"))
}
