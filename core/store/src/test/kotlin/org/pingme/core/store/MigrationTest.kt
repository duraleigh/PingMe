// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.core.store.db.PingMeDatabase
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The migration harness (BUILD_PLAN.md P1.6). Every schema version ever shipped must
 * upgrade to the current one through PingMeDatabase.MIGRATIONS and end up exactly like a
 * fresh current database. Adding a version without its migration or its exported schema
 * fails here.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun helper(file: File) =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            file,
            BundledSQLiteDriver(),
            PingMeDatabase::class,
        )

    @Test
    fun everySchemaVersionHasItsExportedSchema() {
        val exported =
            File("schemas/${PingMeDatabase::class.java.name}")
                .listFiles()
                .orEmpty()
                .map { it.nameWithoutExtension.toInt() }
                .toSet()
        assertEquals((1..PingMeDatabase.VERSION).toSet(), exported)
    }

    @Test
    fun everyVersionMigratesToTheCurrentSchema() {
        for (version in 1..PingMeDatabase.VERSION) {
            val file = temp.root.resolve("v$version.db")
            val helper = helper(file)
            helper.createDatabase(version).close()
            helper.runMigrationsAndValidate(PingMeDatabase.VERSION, PingMeDatabase.MIGRATIONS.toList()).close()
        }
    }

    @Test
    fun aMigratedDatabaseOpensWithRoomAndSearchWorks() =
        runBlocking {
            val file = temp.root.resolve("oldest.db")
            helper(file).createDatabase(1).close()
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val db =
                PingMeDatabase.configure(
                    Room.databaseBuilder(context, PingMeDatabase::class.java, file.absolutePath),
                )
            // Opening creates the search index, which is plain SQL outside Room's schema.
            assertTrue(MessageRepository(db).search("anything").first().isEmpty())
            db.close()
        }
}
