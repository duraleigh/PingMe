// SPDX-License-Identifier: AGPL-3.0-or-later
import java.net.URI

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.pingme.core.service"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:store"))
    api(project(":core:connector-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    // Tests build the store directly on an in-memory database.
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.datastore.preferences)
}

// Refreshes the vendored ClearURLs rules and their licence (UI_DESIGN.md 10.11). Run by hand.
tasks.register("updateClearUrls") {
    description = "Downloads the latest ClearURLs rules into src/main/assets/clearurls"
    doLast {
        val dir = layout.projectDirectory.dir("src/main/assets/clearurls").asFile
        dir.mkdirs()
        mapOf(
            "https://rules2.clearurls.xyz/data.minify.json" to "data.minify.json",
            "https://raw.githubusercontent.com/ClearURLs/Rules/master/LICENSE" to "LICENSE",
        ).forEach { (url, name) ->
            URI(url).toURL().openStream().use { dir.resolve(name).outputStream().use { out -> it.copyTo(out) } }
        }
        println("ClearURLs rules refreshed; update the date in README.md")
    }
}
