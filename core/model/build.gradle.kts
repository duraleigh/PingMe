// SPDX-License-Identifier: AGPL-3.0-or-later
// Plain Kotlin, no Android imports (BUILD_PLAN.md P1.1).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
