// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pingme.app.chat.attach.OutgoingFiles
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.Diag
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Person
import org.pingme.core.service.ChatActions
import org.pingme.core.service.MessageActions
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.SettingsRepository
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Somewhere a share can go: a chat PingMe has, or a person a new chat can start with. */
sealed interface ShareTarget {
    val key: String
    val title: String
    val network: NetworkId

    data class ToChat(
        val chat: Chat,
        override val network: NetworkId,
    ) : ShareTarget {
        override val key get() = chat.id.value
        override val title get() = chat.nameOverride ?: chat.title
    }

    data class ToPerson(
        val person: Person,
        override val network: NetworkId,
    ) : ShareTarget {
        override val key get() = "person/" + person.id.value
        override val title get() = person.displayName
    }
}

data class ShareUiState(
    val text: String = "",
    val fileCount: Int = 0,
    val query: String = "",
    /** Words the user adds to go with what is shared (owner, Gate G7). */
    val note: String = "",
    val chats: List<ShareTarget.ToChat> = emptyList(),
    val people: List<ShareTarget.ToPerson> = emptyList(),
    val picked: Set<String> = emptySet(),
    val sending: Boolean = false,
    val empty: Boolean = false,
)

/**
 * The share picker (owner, Gate G3): chats first, people when searched, as many as wanted.
 * Each one gets its own send, one after the other, as Google Messages does.
 */
@HiltViewModel
class ShareViewModel
    @Inject
    constructor(
        private val requests: ShareRequests,
        chats: ChatRepository,
        accounts: AccountRepository,
        contacts: ContactRepository,
        private val registry: ConnectorRegistry,
        private val chatActions: ChatActions,
        private val messageActions: MessageActions,
        private val files: OutgoingFiles,
        messages: MessageRepository,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val payload = requests.take()
        private val form =
            MutableStateFlow(
                ShareUiState(
                    text = payload?.text.orEmpty(),
                    fileCount =
                        payload?.uris?.size ?: 0,
                    empty = payload == null,
                ),
            )
        private val done = Channel<ChatId?>(Channel.BUFFERED)

        /** The one chat the share went to, or null when it went to several: the inbox then. */
        val finished = done.receiveAsFlow()

        private val failures = Channel<String?>(Channel.BUFFERED)
        val failed = failures.receiveAsFlow()

        /** How much each chat is used: messages in the last month, and shares from this picker. */
        private val use =
            combine(messages.activitySince(Clock.System.now() - RECENT), settings.shareCounts) { talked, shared ->
                Use(talked, shared)
            }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val state: StateFlow<ShareUiState> =
            combine(form, accounts.accounts(), chats.all(), use) { f, accountList, chatList, use ->
                val networks = accountList.associate { it.id to it.network }
                val q = f.query.trim()
                val rows =
                    rank(chatList, use)
                        .mapNotNull { chat -> networks[chat.accountId]?.let { ShareTarget.ToChat(chat, it) } }
                        .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
                Triple(f.copy(chats = rows), accountList, q)
            }.flatMapLatest { (f, accountList, q) ->
                if (q.isEmpty()) return@flatMapLatest flowOf(f)
                val startable = accountList.filter { registry[it.network]?.capabilities?.startConversation == true }
                peopleOn(startable, contacts, q).let { flow ->
                    combine(flow, flowOf(f)) { people, st -> st.copy(people = people) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), form.value)

        /**
         * The chats most shared with and most talked in first, then the rest by recency
         * (owner, 2026-10-10: "most frequently interacted with or most frequently shared with",
         * not the latest messages). A merged chat stands for its members, which are left out,
         * so no one is listed twice (owner, 2026-10-10: Parker Aiken twice).
         */
        private fun rank(
            chatList: List<Chat>,
            use: Use,
        ): List<Chat> =
            chatList
                .filter { !it.isArchived && it.mergedInto == null }
                .distinctBy { it.id }
                .sortedWith(compareByDescending<Chat> { use.score(it.id) }.thenByDescending { it.lastActivityAt })

        private fun peopleOn(
            startable: List<Account>,
            contacts: ContactRepository,
            q: String,
        ) = if (startable.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(startable.map { account -> contacts.people(account.id) }) { lists ->
                lists
                    .flatMapIndexed { i, people ->
                        people
                            .filter {
                                it.displayName.contains(q, ignoreCase = true) ||
                                    it.networkHandle.contains(q, ignoreCase = true)
                            }.map { ShareTarget.ToPerson(it, startable[i].network) }
                    }.take(MAX_PEOPLE)
            }
        }

        fun search(text: String) = form.update { it.copy(query = text) }

        fun note(text: String) = form.update { it.copy(note = text) }

        fun toggle(target: ShareTarget) =
            form.update {
                it.copy(
                    picked =
                        if (target.key in
                            it.picked
                        ) {
                            it.picked - target.key
                        } else {
                            it.picked + target.key
                        },
                )
            }

        /** Sends the share to every picked target, one message each, then says where it went. */
        fun send() {
            val share = payload
            val s = state.value
            val targets = (s.chats + s.people).filter { it.key in s.picked }
            // Every step goes to the diagnostic file (owner, 2026-10-06: "Send gets pressed.
            // But nothing gets sent as a message").
            Diag.note(
                TAG,
                "Send: ${targets.size} target(s), ${share?.uris?.size ?: 0} file(s), " +
                    "${share?.text?.length ?: 0} characters of text, sending=${s.sending}",
            )
            if (share == null || targets.isEmpty() || s.sending) return
            form.update { it.copy(sending = true) }
            viewModelScope.launch {
                val went = targets.mapNotNull { sendOne(it, share, s.note) }
                form.update { it.copy(sending = false) }
                // Nothing went: the picker stays, with the reason on screen, instead of closing
                // as if it had worked.
                if (went.isEmpty()) return@launch
                requests.finish()
                done.send(went.singleOrNull())
            }
        }

        /** One target's send; the chat it went to, or null with the reason raised for the screen. */
        private suspend fun sendOne(
            target: ShareTarget,
            share: SharePayload,
            note: String,
        ): ChatId? =
            try {
                val chatId = chatFor(target)
                // Copied once per send: a network may keep or move the file it is given.
                val attachments = share.uris.mapNotNull { files.copy(it) }
                Diag.note(TAG, "To ${chatId.value}: ${attachments.size} of ${share.uris.size} file(s) readable")
                // What is shared goes first (a picture can take a while to upload), and the
                // note follows as its own message once it is away, so the other side never
                // reads the words and then waits for the picture (owner, Gate G7).
                if (share.text.isBlank() && attachments.isEmpty()) {
                    failures.send(UNREADABLE)
                    null
                } else {
                    val message = messageActions.send(chatId, share.text, attachments = attachments)
                    Diag.note(TAG, "Sent ${message.id.value} to ${chatId.value}: ${message.status}")
                    note.trim().takeIf { it.isNotEmpty() }?.let { messageActions.send(chatId, it) }
                    settings.noteShare(chatId)
                    chatId
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Diag.note(TAG, "Share to $target failed: $e")
                failures.send(e.message)
                null
            }

        private suspend fun chatFor(target: ShareTarget): ChatId =
            when (target) {
                is ShareTarget.ToChat -> target.chat.id
                is ShareTarget.ToPerson -> chatActions.startChat(target.person.accountId, target.person.networkHandle)
            }

        /** Messages in the last month and picker shares, by chat; a share counts as [SHARE_WEIGHT] messages. */
        private class Use(
            private val talked: Map<ChatId, Int>,
            private val shared: Map<String, Int>,
        ) {
            fun score(id: ChatId): Int = (talked[id] ?: 0) + SHARE_WEIGHT * (shared[id.value] ?: 0)
        }

        private companion object {
            const val STOP_AFTER = 5_000L
            const val SHARE_WEIGHT = 5
            val RECENT = 30.days
            const val MAX_PEOPLE = 30
            const val TAG = "PingMeShare"

            /** Shown when the shared file could not be read and there was no text to send instead. */
            const val UNREADABLE = "the shared file could not be read"
        }
    }

/** For the picker's chips: which account a target belongs to. */
val ShareTarget.accountId: AccountId
    get() =
        when (this) {
            is ShareTarget.ToChat -> chat.accountId
            is ShareTarget.ToPerson -> person.accountId
        }
