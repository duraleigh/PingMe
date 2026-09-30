// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.pingme.connectors.messenger"
}

dependencies {
    implementation(project(":core:connector-api"))
}
