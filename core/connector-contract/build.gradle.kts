// SPDX-License-Identifier: AGPL-3.0-or-later
// Test-only library: the connector contract test (BUILD_PLAN.md P1.3). Connector modules
// depend on it with testImplementation; it never ships in the app.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.pingme.core.connector.contract"
}

dependencies {
    api(project(":core:connector-api"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
}
