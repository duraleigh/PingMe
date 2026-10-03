// SPDX-License-Identifier: AGPL-3.0-or-later
// The Go bridge as a Gradle module (BUILD_PLAN.md P3.1). build.sh binds libgm with gomobile
// into build/gobridge.aar, which this module publishes as its one artifact. A library
// module may not depend on a local .aar file directly (the Android Gradle Plugin refuses
// to bundle it), so :connectors:gmessages depends on this module instead.
import java.util.Properties

val goBridgeAar = file("build/gobridge.aar")

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

// Runs build.sh, which skips the bind when the Go sources have not changed.
val buildGoBridge =
    tasks.register<Exec>("buildGoBridge") {
        description = "Builds the Go bridge AAR with gomobile (BUILD_PLAN.md P3.1)."
        workingDir = projectDir
        commandLine("./build.sh")
        androidHome?.let { environment("ANDROID_HOME", it) }
        inputs.dir(layout.projectDirectory.dir("gm"))
        inputs.dir(layout.projectDirectory.dir("wa"))
        inputs.dir(layout.projectDirectory.dir("ig"))
        inputs.dir(layout.projectDirectory.dir("sig"))
        inputs.file(layout.projectDirectory.file("libsignal/VERSION"))
        inputs.files("go.mod", "go.sum", "build.sh")
        outputs.file(goBridgeAar)
    }

// What depending on project(":gobridge") resolves to: the AAR, built when needed.
configurations.maybeCreate("default")
artifacts.add("default", goBridgeAar) {
    type = "aar"
    builtBy(buildGoBridge)
}
