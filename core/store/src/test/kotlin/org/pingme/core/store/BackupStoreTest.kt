// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.store.db.PingMeDatabase
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/** Backup and restore of the database (BUILD_PLAN.md P2.6). */
@RunWith(RobolectricTestRunner::class)
class BackupStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<PingMeDatabase>()

    private fun open() =
        PingMeDatabase
            .configure(Room.databaseBuilder(context, PingMeDatabase::class.java, NAME))
            .also { opened += it }

    private val account =
        Account(
            AccountId("a"),
            NetworkId.DEMO,
            "Demo",
            0,
            ConnectionState.Connected,
            true,
            NotificationMode.NORMAL,
            "ref",
        )

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(NAME)
    }

    @Test
    fun aBackupBringsBackWhatWasThere() =
        runBlocking {
            val db = open()
            AccountRepository(db).upsert(account)
            val backup = ByteArrayOutputStream().also { BackupStore(context, db).export(it) }.toByteArray()
            AccountRepository(db).delete(account.id)
            assertNull(BackupStore(context, db).restore(backup.inputStream()))
            assertNotNull(AccountRepository(open()).get(account.id))
        }

    @Test
    fun anythingElseIsRefusedAndNothingChanges() =
        runBlocking {
            val db = open()
            AccountRepository(db).upsert(account)
            val problem = BackupStore(context, db).restore("not a database".byteInputStream())
            assertEquals(RestoreProblem.NOT_A_BACKUP, problem)
            assertNotNull(AccountRepository(db).get(account.id))
        }

    private companion object {
        val NAME = PingMeDatabase.NAME
    }
}
