// SPDX-License-Identifier: AGPL-3.0-or-later
// The Google Messages connector (BUILD_PLAN.md P3.2) over the Go bridge (P3.1): libgm
// compiled with gomobile into gobridge/build/gobridge.aar, which is built here on demand
// and never committed.
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.pingme.connectors.gmessages"
}

val goBridgeDir = rootProject.file("gobridge")
val goBridgeAar = goBridgeDir.resolve("build/gobridge.aar")

// Where the Android SDK (and its NDK) is: the environment first, then local.properties.
val androidHome: String? =
    providers.environmentVariable("ANDROID_HOME").orNull
        ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: Properties()
            .apply {
                rootProject
                    .file("local.properties")
                    .takeIf { it.exists() }
                    ?.inputStream()
                    ?.use { load(it) }
            }.getProperty("sdk.dir")

// Runs gobridge/build.sh, which skips the bind when the Go sources have not changed.
val buildGoBridge =
    tasks.register<Exec>("buildGoBridge") {
        description = "Builds the Go bridge AAR with gomobile (BUILD_PLAN.md P3.1)."
        workingDir = goBridgeDir
        commandLine("./build.sh")
        androidHome?.let { environment("ANDROID_HOME", it) }
        inputs.dir(goBridgeDir.resolve("gm"))
        inputs.files(goBridgeDir.resolve("go.mod"), goBridgeDir.resolve("go.sum"), goBridgeDir.resolve("build.sh"))
        outputs.file(goBridgeAar)
    }

dependencies {
    api(project(":core:connector-api"))
    implementation(files(goBridgeAar).builtBy(buildGoBridge))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:connector-contract"))
}
