// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
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
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.model.Transport
import org.pingme.core.store.db.PingMeDatabase
import org.robolectric.RobolectricTestRunner
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Base for store tests: a fresh in-memory database on the bundled SQLite (the same engine
 * the app ships, so FTS5 is available), a fresh settings file, and a fixed clock.
 */
@RunWith(RobolectricTestRunner::class)
abstract class StoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    protected lateinit var db: PingMeDatabase
    private lateinit var dataStoreScope: CoroutineScope

    protected val now: Instant = Instant.parse("2026-09-30T12:00:00Z")
    private val fixedClock =
        object : Clock {
            override fun now() = now
        }

    protected lateinit var settings: SettingsRepository
    protected lateinit var accounts: AccountRepository
    protected lateinit var chats: ChatRepository
    protected lateinit var messages: MessageRepository
    protected lateinit var contacts: ContactRepository

    @Before
    fun openStore() {
        db =
            PingMeDatabase.configure(
                Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PingMeDatabase::class.java),
            )
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore =
            PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                temp.root.resolve("settings.preferences_pb")
            }
        settings = SettingsRepository(dataStore, db)
        accounts = AccountRepository(db)
        chats = ChatRepository(db, settings, fixedClock)
        messages = MessageRepository(db)
        contacts = ContactRepository(db)
    }

    @After
    fun closeStore() {
        db.close()
        dataStoreScope.cancel()
    }

    protected fun account(
        id: String,
        network: NetworkId = NetworkId.DEMO,
        showInInbox: Boolean = true,
    ) = Account(
        id = AccountId(id),
        network = network,
        displayName = id,
        colorArgb = 0,
        state = ConnectionState.Connected,
        showInInbox = showInInbox,
        notificationMode = NotificationMode.NORMAL,
        credentialRef = "cred-$id",
    )

    protected fun chat(
        id: String,
        account: String,
        unread: Int = 0,
        activity: Instant = now,
    ) = Chat(
        id = ChatId(id),
        accountId = AccountId(account),
        kind = ChatKind.DIRECT,
        title = id,
        participants = listOf(PersonId("p-$id")),
        unreadCount = unread,
        lastActivityAt = activity,
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
        networkRemoteId = "remote-$id",
    )

    protected fun message(
        id: String,
        chat: String,
        body: String? = "hello",
        sender: String = "p-sam",
        sentAt: Instant = now,
        attachments: List<Attachment> = emptyList(),
    ) = Message(
        id = MessageId(id),
        chatId = ChatId(chat),
        senderId = PersonId(sender),
        sentAt = sentAt,
        receivedAt = sentAt,
        body = body,
        kind = MessageKind.TEXT,
        attachments = attachments,
        replyTo = null,
        quote = null,
        editedAt = null,
        deletedForEveryone = false,
        status = MessageStatus.Delivered,
        reactions = emptyList(),
        transport = Transport.NETWORK,
        networkRemoteId = "remote-$id",
        linkPreview = null,
        isOutgoing = false,
    )

    protected fun attachment(
        id: String,
        fileName: String?,
    ) = Attachment(
        id = AttachmentId(id),
        kind = AttachmentKind.FILE,
        mimeType = "application/pdf",
        fileName = fileName,
        sizeBytes = 1_000,
        localPath = null,
        remoteRef = "remote-$id",
        durationMs = null,
        width = null,
        height = null,
        isEphemeral = false,
        savedAt = null,
    )

    protected fun person(
        id: String,
        account: String,
        name: String,
        phone: String? = null,
    ) = Person(
        id = PersonId(id),
        accountId = AccountId(account),
        displayName = name,
        phoneNumber = phone,
        networkHandle = phone ?: id,
        avatarPath = null,
        contactId = null,
    )
}
