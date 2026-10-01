// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.Person
import org.pingme.core.model.Transport
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.SettingsRepository
import org.pingme.core.store.db.PingMeDatabase
import org.robolectric.RobolectricTestRunner
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Base for service tests: an in-memory store on the bundled SQLite, and the pieces around it. */
@RunWith(RobolectricTestRunner::class)
abstract class ServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    protected val context: Context get() = ApplicationProvider.getApplicationContext()
    protected lateinit var db: PingMeDatabase
    protected lateinit var scope: CoroutineScope

    protected val now: Instant = Instant.parse("2026-09-30T12:00:00Z")
    protected val clock =
        object : Clock {
            override fun now() = now
        }

    protected lateinit var settings: SettingsRepository
    protected lateinit var accounts: AccountRepository
    protected lateinit var chats: ChatRepository
    protected lateinit var messages: MessageRepository
    protected lateinit var contacts: ContactRepository
    protected lateinit var typing: TypingTracker
    protected lateinit var reactionFeed: ReactionFeed
    protected lateinit var applier: EventApplier
    protected lateinit var router: NotificationRouter
    protected lateinit var keeper: CountingKeeper
    protected lateinit var history: CountingHistory

    protected val accountId = AccountId("acc")

    @Before
    fun openStore() {
        db = PingMeDatabase.configure(Room.inMemoryDatabaseBuilder(context, PingMeDatabase::class.java))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings =
            SettingsRepository(
                PreferenceDataStoreFactory.create(scope = scope) { temp.root.resolve("s.preferences_pb") },
                db,
            )
        accounts = AccountRepository(db)
        chats = ChatRepository(db, settings, clock)
        messages = MessageRepository(db)
        contacts = ContactRepository(db)
        typing = TypingTracker(scope)
        reactionFeed = ReactionFeed()
        applier = EventApplier(accounts, chats, messages, contacts, typing, reactionFeed)
        router = NotificationRouter(context, chats, clock)
        keeper = CountingKeeper(context, settings, messages, clock)
        history = CountingHistory(context, messages)
    }

    /** Background work stops before the database closes, so nothing writes to a closed store. */
    @After
    fun closeStore() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    /** Waits in real time for [condition]; connections run on their own threads. */
    protected suspend fun eventually(condition: suspend () -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (!condition()) delay(10.milliseconds)
            }
        }

    protected fun account(
        state: ConnectionState = ConnectionState.Connected,
        credentialRef: String = "cred",
    ) = Account(accountId, NetworkId.DEMO, "Demo", 0, state, true, NotificationMode.NORMAL, credentialRef)

    protected fun sam() = Person(accountId.person("sam"), accountId, "Sam Ortiz", null, "sam", null, null)

    protected fun chatSnapshot(
        remote: String = "c1",
        unread: Int = 0,
        title: String = "Sam Ortiz",
    ) = ChatSnapshot(
        id = accountId.chat(remote),
        accountId = accountId,
        kind = ChatKind.DIRECT,
        title = title,
        participants = listOf(sam()),
        unreadCount = unread,
        lastActivityAt = now,
        folder = null,
        spaceId = null,
        networkRemoteId = remote,
    )

    protected fun messageSnapshot(
        remote: String,
        chatRemote: String = "c1",
        body: String = "hi",
        outgoing: Boolean = false,
        sentAt: Instant = now,
    ) = MessageSnapshot(
        message =
            Message(
                id = accountId.message(remote),
                chatId = accountId.chat(chatRemote),
                senderId = if (outgoing) accountId.person("me") else sam().id,
                sentAt = sentAt,
                receivedAt = sentAt,
                body = body,
                kind = MessageKind.TEXT,
                attachments = emptyList(),
                replyTo = null,
                quote = null,
                editedAt = null,
                deletedForEveryone = false,
                status = if (outgoing) MessageStatus.Sent else MessageStatus.Delivered,
                reactions = emptyList(),
                transport = Transport.NETWORK,
                networkRemoteId = remote,
                linkPreview = null,
                isOutgoing = outgoing,
            ),
        sender = if (outgoing) null else sam(),
    )
}

/** Counts downloads instead of asking Android's job system, which tests do not start. */
class CountingKeeper(
    context: Context,
    settings: SettingsRepository,
    messages: MessageRepository,
    clock: kotlin.time.Clock,
) : MediaKeeper(context, settings, messages, clock) {
    val downloads = mutableListOf<org.pingme.core.model.AttachmentId>()

    override fun download(id: org.pingme.core.model.AttachmentId) {
        downloads += id
    }
}

/** Records which chats would fetch their history, instead of asking Android's job system. */
class CountingHistory(
    context: Context,
    messages: MessageRepository,
) : HistorySync(context, messages) {
    val backfilled = mutableListOf<org.pingme.core.model.ChatId>()

    override fun backfill(chatId: org.pingme.core.model.ChatId) {
        backfilled += chatId
    }
}
