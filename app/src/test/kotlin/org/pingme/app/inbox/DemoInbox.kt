// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    val applier = EventApplier(accounts, chats, messages, contacts, typing, reactions)
    val controls = DemoControls()
    val demo = DemoConnector(controls, MemoryCredentials(), dir.resolve("media"), Clock.System)
    val registry = ConnectorRegistry(mapOf(NetworkId.DEMO to demo))
    val actions = ChatActions(chats, messages, accounts, registry, applier)
    val pins = PinnedMessageRepository(db)
    val messageActions = MessageActions(chats, messages, pins, accounts, registry, applier, Clock.System)
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

    /** Adds the demo account and copies its cast and history into the store, as a first sync does. */
    suspend fun seed() {
        accounts.upsert(account)
        val snapshots = demo.syncChats(account.id)
        applier.applyChats(snapshots)
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
        )

    fun listViewModel(route: ChatListRoute) =
        ChatListViewModel(chats, messages, accounts, typing, actions, Clock.System, route.toSavedState())

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
        )

    fun searchViewModel() = SearchViewModel(chats, messages, Clock.System, SavedStateHandle())

    fun newChatViewModel(group: Boolean) =
        NewChatViewModel(
            accounts,
            contacts,
            ConnectorRegistry(mapOf(NetworkId.DEMO to demo)),
            actions,
            SavedStateHandle(mapOf("group" to group)),
        )

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

    fun close() {
        scope.cancel()
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
