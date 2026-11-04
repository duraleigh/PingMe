// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import org.pingme.app.chat.attach.OutgoingFiles
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.Diag
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Person
import org.pingme.core.service.ApplicationScope
import org.pingme.core.service.ChatActions
import org.pingme.core.service.MessageActions
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import javax.inject.Inject

/** Somewhere a share can go: a chat PingMe has, or a person a new chat can start with. */
sealed interface ShareTarget {
    val key: String
    val title: String
    val network: NetworkId

    data class ToChat(
        val chat: Chat,
        override val network: NetworkId,
        /** The chat as the inbox shows it: photo, faces, and its last message (owner, 2026-11-04). */
        val row: org.pingme.app.inbox.ChatRow? = null,
        /** A one-to-one chat's person, by their number or username, for Send to a group. */
        val handle: String? = null,
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
    /** Everyone picked can be put in one new group: two or more, on one account that makes groups. */
    val canGroup: Boolean = false,
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
        messages: org.pingme.core.store.MessageRepository,
        private val registry: ConnectorRegistry,
        private val chatActions: ChatActions,
        private val messageActions: MessageActions,
        private val files: OutgoingFiles,
        @param:ApplicationContext private val context: Context,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : ViewModel() {
        private val payload = requests.take()
        private val form =
            MutableStateFlow(
                ShareUiState(
                    text = payload?.text.orEmpty(),
                    fileCount =
                        payload?.uris?.size ?: 0,
                    empty = payload == null,
                    picked = setOfNotNull(payload?.chatKey),
                    // Shared words go into the box to be read and changed before sending, as in
                    // Google Messages; with files, the box is a message to go with them.
                    note = payload?.takeIf { it.uris.isEmpty() }?.text.orEmpty(),
                ),
            )
        private val done = Channel<ChatId?>(Channel.BUFFERED)

        /** The one chat the share went to, or null when it went to several: the inbox then. */
        val finished = done.receiveAsFlow()

        private val failures = Channel<String?>(Channel.BUFFERED)
        val failed = failures.receiveAsFlow()

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val state: StateFlow<ShareUiState> =
            combine(form, accounts.accounts(), chats.all(), messages.lastMessages(), contacts.inChats()) {
                f,
                accountList,
                chatList,
                last,
                people,
                ->
                val networks = accountList.associate { it.id to it.network }
                val q = f.query.trim()
                val rows =
                    rank(chatList)
                        .mapNotNull { chat -> networks[chat.accountId]?.let { target(chat, it, last, people) } }
                        .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
                Triple(f.copy(chats = rows), accountList, q)
            }.flatMapLatest { (f, accountList, q) ->
                // Everyone, A to Z, under the recent chats, as in Google Messages; a search narrows them.
                val startable = accountList.filter { registry[it.network]?.capabilities?.startConversation == true }
                combine(peopleOn(startable, contacts, q), flowOf(f)) { found, st ->
                    st.copy(people = found).let { it.copy(canGroup = groupFor(it) != null) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), form.value)

        private fun target(
            chat: Chat,
            network: NetworkId,
            last: Map<ChatId, org.pingme.core.store.LastMessage>,
            people: Map<org.pingme.core.model.PersonId, Person>,
        ): ShareTarget.ToChat {
            val row =
                org.pingme.app.inbox.ChatRow(
                    chat,
                    network,
                    last[chat.id],
                    typing = false,
                    photo =
                        org.pingme.app.inbox
                            .photoFor(chat, people),
                    faces =
                        org.pingme.app.inbox
                            .facesFor(chat, people),
                )
            val other =
                chat.participants
                    .mapNotNull { people[it] }
                    .firstOrNull { it.displayName != YOU }
                    ?.networkHandle
                    ?.takeIf { chat.kind == org.pingme.core.model.ChatKind.DIRECT }
            return ShareTarget.ToChat(chat, network, row, other)
        }

        /** The one account and everyone's numbers, when the picked can become one new group. */
        private fun groupFor(s: ShareUiState): Pair<AccountId, List<String>>? {
            val picked = (s.chats + s.people).filter { it.key in s.picked }
            val account = picked.map { it.accountId }.distinct().singleOrNull()
            val handles =
                picked.map {
                    when (it) {
                        is ShareTarget.ToChat -> it.handle
                        is ShareTarget.ToPerson -> it.person.networkHandle
                    }
                }
            val network = picked.firstOrNull()?.network
            val makesGroups = network != null && registry[network]?.capabilities?.createGroup == true
            val everyoneKnown = handles.none { it == null }
            return account
                ?.takeIf { picked.size >= 2 && makesGroups && everyoneKnown }
                ?.let { it to handles.filterNotNull().distinct() }
        }

        /**
         * The latest conversation first, the way Google Messages lists them (owner, 2026-11-01,
         * replacing the last-sent and most-used orders before it). A merged chat stands for its
         * members, which are left out, so no one is listed twice.
         */
        private fun rank(chatList: List<Chat>): List<Chat> =
            chatList
                .filter { !it.isArchived && it.mergedInto == null }
                .distinctBy { it.id }
                .sortedByDescending { it.lastActivityAt }

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
                                q.isEmpty() ||
                                    it.displayName.contains(q, ignoreCase = true) ||
                                    it.networkHandle.contains(q, ignoreCase = true)
                            }.map { ShareTarget.ToPerson(it, startable[i].network) }
                    }.sortedBy { it.title.lowercase() }
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
        fun send() = sendTo(group = false)

        /**
         * Puts everyone picked in one new group and sends the share there, as Google Messages'
         * "Send to a group" does (owner, 2026-11-04).
         */
        fun sendToGroup() = sendTo(group = true)

        private fun sendTo(group: Boolean) {
            val share = payload
            // What is picked and typed comes straight from the taps: the listed state can lag them
            // while the people list refreshes, and a Send right after a pick then found nobody.
            val s = form.value.let { taps -> state.value.copy(picked = taps.picked, note = taps.note) }
            val targets = (s.chats + s.people).filter { it.key in s.picked }
            // Every step goes to the diagnostic file (owner, 2026-10-06: "Send gets pressed.
            // But nothing gets sent as a message").
            Diag.note(
                TAG,
                "Send${if (group) " to a group" else ""}: ${targets.size} target(s), " +
                    "${share?.uris?.size ?: 0} file(s), " +
                    "${share?.text?.length ?: 0} characters of text, sending=${s.sending}",
            )
            if (share == null || targets.isEmpty() || s.sending) return
            form.update { it.copy(sending = true) }
            viewModelScope.launch { deliver(share, s, targets, group) }
        }

        /** Finds or makes the chats, then sends on the app's scope while the screen closes. */
        private suspend fun deliver(
            share: SharePayload,
            s: ShareUiState,
            targets: List<ShareTarget>,
            group: Boolean,
        ) {
            val words = if (share.uris.isEmpty()) s.note else share.text
            if (words.isBlank() && share.uris.none { files.canRead(it) }) {
                failures.send(UNREADABLE)
                form.update { it.copy(sending = false) }
                return
            }
            val chatsToSend =
                if (group) {
                    listOfNotNull(groupFor(s)?.let { (account, handles) -> groupOrSay(account, handles) })
                } else {
                    targets.mapNotNull { chatOrSay(it) }
                }
            form.update { it.copy(sending = false) }
            if (chatsToSend.isEmpty()) return
            // The send goes on without this screen, which closes now: the bubble and its
            // progress are in the chat (owner, 2026-11-01: "take me directly to the chat").
            appScope.launch { chatsToSend.forEach { sendOne(it, share, s.note) } }
            requests.finish()
            done.send(chatsToSend.singleOrNull())
        }

        /** A new group of everyone picked, or null after saying why the network would not make it. */
        private suspend fun groupOrSay(
            account: AccountId,
            handles: List<String>,
        ): ChatId? =
            try {
                chatActions.createGroup(account, "", handles)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Diag.note(TAG, "No group for ${handles.size} people: $e")
                failures.send(e.message)
                null
            }

        /** The chat to send to; only someone new takes a moment, while the network makes it. */
        private suspend fun chatOrSay(target: ShareTarget): ChatId? =
            try {
                chatFor(target)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Diag.note(TAG, "No chat for $target: $e")
                failures.send(e.message)
                null
            }

        /** One chat's send, after the screen has gone: a failure shows on its bubble, or else in a toast. */
        private suspend fun sendOne(
            chatId: ChatId,
            share: SharePayload,
            note: String,
        ) {
            try {
                // Copied once per send: a network may keep or move the file it is given.
                val attachments = share.uris.mapNotNull { files.copy(it) }
                Diag.note(TAG, "To ${chatId.value}: ${attachments.size} of ${share.uris.size} file(s) readable")
                // What is shared goes first (a picture can take a while to upload), and the
                // note follows as its own message once it is away, so the other side never
                // reads the words and then waits for the picture (owner, Gate G7).
                // Shared words alone were in the box to be changed, so what the box says is what goes.
                val text = if (share.uris.isEmpty()) note else share.text
                if (text.isBlank() && attachments.isEmpty()) {
                    tell(UNREADABLE)
                    return
                }
                val message = messageActions.send(chatId, text, attachments = attachments)
                Diag.note(TAG, "Sent ${message.id.value} to ${chatId.value}: ${message.status}")
                if (share.uris.isNotEmpty()) {
                    note.trim().takeIf { it.isNotEmpty() }?.let {
                        messageActions.send(
                            chatId,
                            it,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Diag.note(TAG, "Share to ${chatId.value} failed: $e")
                tell(e.message ?: UNREADABLE)
            }
        }

        private suspend fun tell(reason: String) =
            withContext(Dispatchers.Main) {
                android.widget.Toast
                    .makeText(context, reason, android.widget.Toast.LENGTH_LONG)
                    .show()
            }

        private suspend fun chatFor(target: ShareTarget): ChatId =
            when (target) {
                is ShareTarget.ToChat -> target.chat.id
                is ShareTarget.ToPerson -> chatActions.startChat(target.person.accountId, target.person.networkHandle)
            }

        private companion object {
            const val STOP_AFTER = 5_000L
            const val YOU = "You"
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
