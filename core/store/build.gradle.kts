// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "org.pingme.core.store"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))

    implementation(libs.androidx.room.runtime)
    // Bundled SQLite: the same, recent SQLite on every phone, with FTS5 (BUILD_PLAN.md P1.2).
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
}

// Unit tests run on the build machine, where the Android build of the bundled SQLite
// cannot load. Its loader reads these two system properties for exactly this case, so
// point it at the host build of the same library version.
val sqliteHostNatives: Configuration =
    configurations.create("sqliteHostNatives") {
        isCanBeConsumed = false
    }
dependencies {
    sqliteHostNatives(libs.androidx.sqlite.bundled.jvm)
}
val sqliteNativesDir = layout.buildDirectory.dir("sqlite-host-natives")

interface ArchiveServices {
    @get:Inject
    val archives: ArchiveOperations
}
val extractSqliteHostNatives =
    tasks.register<Sync>("extractSqliteHostNatives") {
        val archives = objects.newInstance<ArchiveServices>().archives
        from(sqliteHostNatives.elements.map { jars -> jars.map { archives.zipTree(it.asFile) } }) {
            include("natives/**")
            eachFile { path = path.removePrefix("natives/") }
            includeEmptyDirs = false
        }
        into(sqliteNativesDir)
    }
val hostOs = System.getProperty("os.name").lowercase()
val hostArchName = System.getProperty("os.arch").lowercase()
val hostArch = if ("aarch64" in hostArchName || "arm64" in hostArchName) "arm64" else "x64"
val (sqliteNativeSubdir, sqliteNativeName) =
    when {
        "mac" in hostOs -> "osx_$hostArch" to "libsqliteJni.dylib"
        "windows" in hostOs -> "windows_$hostArch" to "sqliteJni.dll"
        else -> "linux_$hostArch" to "libsqliteJni.so"
    }
tasks.withType<Test>().configureEach {
    dependsOn(extractSqliteHostNatives)
    val nativeDir =
        sqliteNativesDir
            .get()
            .dir(sqliteNativeSubdir)
            .asFile.absolutePath
    systemProperty("androidx.sqlite.driver.bundled.path", nativeDir)
    systemProperty("androidx.sqlite.driver.bundled.name", sqliteNativeName)
}
