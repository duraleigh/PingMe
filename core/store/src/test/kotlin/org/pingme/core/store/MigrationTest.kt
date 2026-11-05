// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
    fun aChatThatShowedAsReadIsStampedReadByTheUpgrade() {
        // Chats read under 0.7.0 came back unread after the upgrade (owner, Gate G7, round 2).
        val file = temp.root.resolve("v5.db")
        val helper = helper(file)
        helper.createDatabase(5).use { c ->
            c.execSQL(
                "INSERT INTO accounts (id, network, displayName, colorArgb, state, showInInbox, notificationMode, " +
                    "credentialRef) VALUES ('a', 'DEMO', 'Demo', 0, '{\"type\":\"Connected\"}', 1, 'NORMAL', 'c')",
            )
            c.execSQL(chatRow("read", unread = 0))
            c.execSQL(chatRow("unread", unread = 2))
        }
        helper.runMigrationsAndValidate(PingMeDatabase.VERSION, PingMeDatabase.MIGRATIONS.toList()).use { c ->
            c.prepare("SELECT readUpTo FROM chats WHERE id = 'read'").use { row ->
                assertTrue(row.step())
                assertEquals(ACTIVITY, row.getLong(0))
            }
            c.prepare("SELECT readUpTo FROM chats WHERE id = 'unread'").use { row ->
                assertTrue(row.step())
                assertTrue(row.isNull(0))
            }
        }
    }

    private fun chatRow(
        id: String,
        unread: Int,
    ) = "INSERT INTO chats (id, accountId, kind, title, unreadCount, lastActivityAt, isPinned, isMuted, isArchived, " +
        "isLowPriority, isObscured, avatarSource, networkRemoteId) VALUES ('$id', 'a', 'DIRECT', '$id', $unread, " +
        "$ACTIVITY, 0, 0, 0, 0, 0, '{\"type\":\"Contacts\"}', '$id')"

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

private const val ACTIVITY = 1_700_000_000_000L
