// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.pingme.core.store"
}

dependencies {
    implementation(project(":core:model"))
}
