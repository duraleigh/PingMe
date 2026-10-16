// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentId
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.PersonId
import org.pingme.core.model.Transport
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.participantEntities
import org.pingme.core.store.db.toEntity
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Instant

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

    private val pass = "open sesame".toCharArray()

    @Test
    fun aBackupBringsBackWhatWasThere() =
        runBlocking {
            val db = open()
            AccountRepository(db).upsert(account)
            val backup = ByteArrayOutputStream().also { BackupStore(context, db).export(it, pass) }.toByteArray()
            AccountRepository(db).delete(account.id)
            assertNull(BackupStore(context, db).restore(backup.inputStream(), pass))
            assertNotNull(AccountRepository(open()).get(account.id))
        }

    @Test
    fun theSettingsFileAndTheKeptPicturesComeBackToo() =
        runBlocking {
            val db = open()
            AccountRepository(db).upsert(account)
            val settings =
                context.preferencesDataStoreFile("settings").apply {
                    parentFile?.mkdirs()
                    writeText("prefs v1")
                }
            val media = File(context.filesDir, "ig-media").apply { mkdirs() }
            val picture = File(media, "once.jpg").apply { writeBytes(byteArrayOf(9, 9, 9)) }
            val plain = File(media, "plain.jpg").apply { writeBytes(byteArrayOf(1)) }
            val chat = chat("a/c")
            db.chatDao().upsert(chat.toEntity(), chat.participantEntities())
            val messages = MessageRepository(db)
            messages.upsert(message("a/m", "a/c", attachment("a/once", picture.path, ephemeral = true)))
            messages.upsert(message("a/n", "a/c", attachment("a/plain", plain.path, ephemeral = false)))
            val backup = ByteArrayOutputStream().also { BackupStore(context, db).export(it, pass) }.toByteArray()
            settings.writeText("prefs changed")
            picture.delete()
            plain.delete()
            assertNull(BackupStore(context, db).restore(backup.inputStream(), pass))
            assertEquals("prefs v1", settings.readText())
            val restored = MessageRepository(open())
            val once = restored.get(MessageId("a/m"))!!.attachments.single()
            assertTrue(
                "the kept picture is back, where the restore put it",
                File(once.localPath!!).readBytes().contentEquals(byteArrayOf(9, 9, 9)),
            )
            // Ordinary media was not in the backup: it downloads again when shown.
            assertNull(
                restored
                    .get(MessageId("a/n"))!!
                    .attachments
                    .single()
                    .localPath,
            )
        }

    @Test
    fun theWrongPassphraseIsRefusedAndNothingChanges() =
        runBlocking {
            val db = open()
            AccountRepository(db).upsert(account)
            val backup = ByteArrayOutputStream().also { BackupStore(context, db).export(it, pass) }.toByteArray()
            val problem = BackupStore(context, db).restore(backup.inputStream(), "guess".toCharArray())
            assertEquals(RestoreProblem.WRONG_PASSPHRASE, problem)
            assertNotNull(AccountRepository(db).get(account.id))
        }

    @Test
    fun anOlderPlainDatabaseBackupStillRestores() =
        runBlocking {
            val db = open()
            AccountRepository(db).upsert(account)
            val plain = File(context.cacheDir, "old.db")
            db.useWriterConnection { c ->
                c.usePrepared("VACUUM INTO ?") {
                    it.bindText(1, plain.path)
                    it.step()
                }
            }
            AccountRepository(db).delete(account.id)
            assertNull(BackupStore(context, db).restore(plain.inputStream(), "ignored".toCharArray()))
            assertNotNull(AccountRepository(open()).get(account.id))
        }

    @Test
    fun anythingElseIsRefusedAndNothingChanges() =
        runBlocking {
            val db = open()
            AccountRepository(db).upsert(account)
            val problem = BackupStore(context, db).restore("not a database".byteInputStream(), pass)
            assertEquals(RestoreProblem.NOT_A_BACKUP, problem)
            assertNotNull(AccountRepository(db).get(account.id))
        }

    private fun chat(id: String) =
        Chat(
            id = ChatId(id),
            accountId = account.id,
            kind = ChatKind.DIRECT,
            title = id,
            participants = listOf(PersonId("a/p")),
            unreadCount = 0,
            lastActivityAt = NOW,
            isPinned = false,
            pinOrder = null,
            isMuted = false,
            muteUntil = null,
            isArchived = false,
            isLowPriority = false,
            isObscured = false,
            folder = null,
            spaceId = null,
            mergedInto = null,
            avatarSource = AvatarSource.Contacts,
            nameOverride = null,
            defaultSendAccount = null,
            networkRemoteId = id,
        )

    private fun message(
        id: String,
        chat: String,
        attachment: Attachment,
    ) = Message(
        id = MessageId(id),
        chatId = ChatId(chat),
        senderId = PersonId("a/p"),
        sentAt = NOW,
        receivedAt = NOW,
        body = "",
        kind = MessageKind.IMAGE,
        attachments = listOf(attachment),
        replyTo = null,
        quote = null,
        editedAt = null,
        deletedForEveryone = false,
        status = MessageStatus.Delivered,
        reactions = emptyList(),
        transport = Transport.NETWORK,
        networkRemoteId = id,
        linkPreview = null,
        isOutgoing = false,
    )

    private fun attachment(
        id: String,
        path: String,
        ephemeral: Boolean,
    ) = Attachment(
        id = AttachmentId(id),
        kind = AttachmentKind.IMAGE,
        mimeType = "image/jpeg",
        fileName = "p.jpg",
        sizeBytes = 3,
        localPath = path,
        remoteRef = null,
        durationMs = null,
        width = null,
        height = null,
        isEphemeral = ephemeral,
        savedAt = null,
    )

    private companion object {
        val NAME = PingMeDatabase.NAME
        val NOW = Instant.parse("2026-10-06T12:00:00Z")
    }
}
