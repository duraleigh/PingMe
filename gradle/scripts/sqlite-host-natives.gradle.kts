// SPDX-License-Identifier: AGPL-3.0-or-later
// Applied to modules whose unit tests open the database (see root build.gradle.kts).
//
// Unit tests run on the build machine, where the Android build of the bundled SQLite
// cannot load. Its loader reads two system properties for exactly this case, so point
// it at the host build of the same library version.

interface ArchiveServices {
    @get:Inject
    val archives: ArchiveOperations
}

val sqliteHostNatives: Configuration =
    configurations.create("sqliteHostNatives") {
        isCanBeConsumed = false
    }
dependencies {
    sqliteHostNatives("androidx.sqlite:sqlite-bundled-jvm:${project.extra["sqliteVersion"]}")
}
val sqliteNativesDir = layout.buildDirectory.dir("sqlite-host-natives")
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
