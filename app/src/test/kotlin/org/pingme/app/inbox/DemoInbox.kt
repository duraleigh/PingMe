// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.pingme.app.chat.ChatViewModel
import org.pingme.app.chat.MediaRequests
import org.pingme.connectors.demo.DemoConnector
import org.pingme.connectors.demo.DemoControls
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.chat
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.service.ChatActions
import org.pingme.core.service.EventApplier
import org.pingme.core.service.MessageActions
import org.pingme.core.service.ReactionFeed
import org.pingme.core.service.TypingTracker
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.PinnedMessageRepository
import org.pingme.core.store.SettingsRepository
import org.pingme.core.store.db.PingMeDatabase
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/**
 * The real store and services with the demo network behind them, for inbox tests
 * (BUILD_PLAN.md P2.8: every screen gets a UI test against the demo connector).
 */
class DemoInbox(
    dir: File,
) {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = PingMeDatabase.configure(Room.inMemoryDatabaseBuilder(context, PingMeDatabase::class.java))
    val settings =
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("s.preferences_pb") }, db)
    val accounts = AccountRepository(db)
    val chats = ChatRepository(db, settings, Clock.System)
    val messages = MessageRepository(db)
    val contacts = ContactRepository(db)
    val typing = TypingTracker(scope)
    val reactions = ReactionFeed()
    val applier =
        EventApplier(
            accounts,
            chats,
            messages,
            contacts,
            typing,
            reactions,
            org.pingme.core.service
                .Tapbacks(messages),
        )
    val controls = DemoControls()
    val demo = DemoConnector(controls, MemoryCredentials(), dir.resolve("media"), Clock.System)
    val registry = ConnectorRegistry(mapOf(NetworkId.DEMO to demo))
    val links =
        org.pingme.core.service.links
            .CleanLinks
            .fromAssets(context)
    val presence =
        org.pingme.core.service.notify
            .ChatPresence()
    val router =
        org.pingme.core.service.NotificationRouter(
            context,
            chats,
            messages,
            contacts,
            org.pingme.core.store
                .ChatOverridesRepository(db),
            accounts,
            settings,
            presence,
            Clock.System,
        )
    val actions = ChatActions(chats, messages, accounts, registry, applier, settings, router)
    val pins = PinnedMessageRepository(db)
    val scheduledSends =
        org.pingme.core.store
            .ScheduledSendRepository(db)

    /** Counts wake-ups instead of asking Android's job system, which tests do not start. */
    val alarm = CountingAlarm(context, scheduledSends)
    val messageActions =
        MessageActions(
            chats,
            messages,
            pins,
            accounts,
            registry,
            applier,
            Clock.System,
            scheduledSends,
            alarm,
            settings,
            links,
            CountingPreviews(context),
        )
    val account =
        Account(
            AccountId("demo"),
            NetworkId.DEMO,
            "Demo",
            0,
            ConnectionState.Connected,
            true,
            NotificationMode.NORMAL,
            "ref",
        )

    /**
     * Adds the demo account and copies its cast and history into the store, as a first sync does.
     * With [history] off no messages are stored yet, as right after a login.
     */
    suspend fun seed(history: Boolean = true) {
        accounts.upsert(account)
        val snapshots = demo.syncChats(account.id)
        applier.applyChats(snapshots)
        if (!history) return
        snapshots.forEach { chat ->
            val history = demo.syncMessages(chat.id, before = null, limit = 50)
            applier.apply(ConnectorEvent.HistoryBatch(account.id, chat.id, history, complete = true))
        }
    }

    fun inboxViewModel() =
        InboxViewModel(
            chats,
            messages,
            accounts,
            typing,
            reactions,
            actions,
            InboxBarRepository(settings),
            Clock.System,
            SavedStateHandle(),
            settings,
            org.pingme.app.appearance
                .AppearanceRepository(context, settings),
        ).tracked()

    fun listViewModel(route: ChatListRoute) =
        ChatListViewModel(chats, messages, accounts, typing, actions, Clock.System, route.toSavedState()).tracked()

    /** Downloads straight through the demo connector instead of WorkManager. */
    private val media =
        object : MediaRequests(context) {
            override fun download(id: org.pingme.core.model.AttachmentId) {
                scope.launch {
                    val attachment = messages.attachment(id) ?: return@launch
                    // A test may end mid-download and delete its folder; that download simply stops.
                    val file = runCatching { demo.downloadAttachment(attachment) }.getOrNull() ?: return@launch
                    messages.setAttachmentLocalPath(id, file.absolutePath)
                }
            }
        }

    val files =
        org.pingme.app.chat.attach
            .OutgoingFiles(context)
    val recorder =
        org.pingme.app.chat.voice
            .FakeRecorder(context, dir)
    val gifStore =
        org.pingme.app.chat.gif
            .FakeGifStore(context, dir)

    val transcriber =
        org.pingme.app.chat.voice
            .FakeTranscriber(context)

    val requests =
        org.pingme.app.chat
            .ChatRequests()

    val chatSearch =
        org.pingme.core.store
            .ChatSearchRepository(db)

    val overrides =
        org.pingme.core.store
            .ChatOverridesRepository(db)

    fun chatViewModel(remote: String) =
        ChatViewModel(
            account.id.chat(remote).value,
            chats,
            messages,
            pins,
            accounts,
            contacts,
            typing,
            registry,
            actions,
            messageActions,
            media,
            settings,
            reactions,
            files,
            recorder,
            gifStore,
            chatSearch,
            requests,
            overrides,
            transcriber,
            presence,
            links,
        ).tracked()

    fun detailsViewModel(remote: String) =
        org.pingme.app.details
            .ChatDetailsViewModel(
                account.id.chat(remote).value,
                chats,
                accounts,
                contacts,
                messages,
                pins,
                chatSearch,
                overrides,
                org.pingme.app.appearance
                    .AppearanceRepository(context, settings),
                settings,
                registry,
                actions,
                messageActions,
                Clock.System,
                requests,
            ).tracked()

    fun settingsViewModel() =
        org.pingme.app.settings
            .SettingsViewModel(
                settings,
                accounts,
                chats,
                org.pingme.app.appearance
                    .AppearanceRepository(context, settings),
                InboxBarRepository(settings),
                org.pingme.core.store
                    .BackupStore(context, db),
                MemoryCredentials(),
                context,
                registry,
            ).tracked()

    fun setupViewModel() =
        org.pingme.app.setup
            .SetupViewModel(settings, registry, SavedStateHandle())
            .tracked()

    fun loginViewModel(
        again: AccountId? = null,
        fromSetup: Boolean = false,
    ) = org.pingme.app.login
        .LoginViewModel(
            registry,
            accounts,
            settings,
            Clock.System,
            context,
            SavedStateHandle(
                listOfNotNull<Pair<String, Any>>(
                    "network" to NetworkId.DEMO.name,
                    again?.let { "account" to it.value },
                    "fromSetup" to fromSetup,
                ).toMap(),
            ),
        ).tracked()

    fun searchViewModel() = SearchViewModel(chats, messages, Clock.System, SavedStateHandle()).tracked()

    fun newChatViewModel(group: Boolean) =
        NewChatViewModel(
            accounts,
            contacts,
            ConnectorRegistry(mapOf(NetworkId.DEMO to demo)),
            actions,
            SavedStateHandle(mapOf("group" to group)),
        ).tracked()

    private fun ChatListRoute.toSavedState() =
        SavedStateHandle(
            listOfNotNull<Pair<String, Any>>(
                "kind" to kind,
                spaceId?.let { "spaceId" to it },
                title?.let {
                    "title" to
                        it
                },
            ).toMap(),
        )

    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    private fun <T : androidx.lifecycle.ViewModel> T.tracked() = also { viewModels += it }

    /** View models and background work stop before the database closes. */
    fun close() {
        // View models run on the main thread, so cancel them and let the main looper finish them off
        // rather than blocking the main thread while waiting for them.
        viewModels.forEach { it.viewModelScope.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    private class MemoryCredentials : CredentialStore {
        private val secrets = ConcurrentHashMap<String, ByteArray>()

        override suspend fun save(
            ref: String,
            secret: ByteArray,
        ) {
            secrets[ref] = secret
        }

        override suspend fun load(ref: String) = secrets[ref]

        override suspend fun delete(ref: String) {
            secrets.remove(ref)
        }
    }
}

/** A wake-up for scheduled sends that only counts, since tests do not start Android's job system. */
class CountingAlarm(
    context: Context,
    scheduled: org.pingme.core.store.ScheduledSendRepository,
) : org.pingme.core.service.work.SendAlarm(context, scheduled, Clock.System) {
    var armed = 0

    override suspend fun arm() {
        armed++
    }
}

/** Records which messages would get a link preview fetched, instead of asking Android's job system. */
class CountingPreviews(
    context: Context,
) : org.pingme.core.service.links.PreviewRequests(context) {
    val requested = mutableListOf<org.pingme.core.model.Message>()

    override fun request(message: org.pingme.core.model.Message) {
        requested += message
    }
}
