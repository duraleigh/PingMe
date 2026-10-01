// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.model.Account
import org.pingme.core.model.AttachmentId
import org.pingme.core.model.Capabilities
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.PersonId
import org.pingme.core.service.ChatActions
import org.pingme.core.service.MessageActions
import org.pingme.core.service.TypingTracker
import org.pingme.core.service.work.Work
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.PinnedMessageRepository
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Asks for an attachment's file; a class of its own so tests can stand in for WorkManager. */
open class MediaRequests
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        open fun download(id: AttachmentId) = Work.downloadMedia(context, id)
    }

data class ChatUiState(
    val loading: Boolean = true,
    val chat: Chat? = null,
    val account: Account? = null,
    val capabilities: Capabilities? = null,
    val items: List<ChatItem> = emptyList(),
    /** Display names by person, for group senders, quotes, and "typing". */
    val names: Map<PersonId, String> = emptyMap(),
    val typing: List<String> = emptyList(),
    val pinned: List<Message> = emptyList(),
    /** The other person's number in a one-to-one chat, for the call icons. */
    val phone: String? = null,
    val reactions: ReactionPrefs = ReactionPrefs(),
    /** Messages picked in multi-select; empty when not selecting. */
    val selection: Set<org.pingme.core.model.MessageId> = emptySet(),
    val editing: Message? = null,
    /** Who "you" are here, to tell your reactions from others'. */
    val me: PersonId? = null,
    val replyTo: Message? = null,
    /** False once the network has nothing older to give. */
    val moreHistory: Boolean = true,
) {
    val title: String get() = chat?.let { it.nameOverride ?: it.title }.orEmpty()
}

/** The id a network gives "you" before any message says otherwise. */
internal object SelfId {
    fun of(account: org.pingme.core.model.AccountId) = PersonId("${account.value}/me")
}

/** The quick-reaction bar, the double-tap emoji, and recent picks (UI_DESIGN.md 5.4, 10.5). */
data class ReactionPrefs(
    val quick: List<String> = emptyList(),
    val doubleTap: String = "",
    val recent: List<String> = emptyList(),
)

/** One chat (UI_DESIGN.md 3.2, BUILD_PLAN.md P2.4). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel
    @AssistedInject
    constructor(
        @Assisted chatKey: String,
        private val chats: ChatRepository,
        private val messages: MessageRepository,
        pins: PinnedMessageRepository,
        accounts: AccountRepository,
        contacts: ContactRepository,
        typing: TypingTracker,
        registry: ConnectorRegistry,
        private val chatActions: ChatActions,
        private val messageActions: MessageActions,
        private val media: MediaRequests,
        private val settingsRepo: org.pingme.core.store.SettingsRepository,
        reactionFeed: org.pingme.core.service.ReactionFeed,
        files: org.pingme.app.chat.attach.OutgoingFiles,
    ) : ViewModel() {
        /** Takes the chat id as text: Hilt cannot generate factories for value classes. */
        @AssistedFactory
        interface Factory {
            fun create(chatKey: String): ChatViewModel
        }

        val chatId = ChatId(chatKey)

        /** Press and hold, double tap, delete, select (UI_DESIGN.md 3.3). */
        val menu = MessageMenu(viewModelScope, messageActions)

        /** Photos, files, places, and contacts waiting to go with the next message (UI_DESIGN.md 5.8). */
        val outbox =
            org.pingme.app.chat.attach
                .Outbox(viewModelScope, files)

        /** How far each sending message's media has got. */
        val uploads = messageActions.progress

        /** Chats a message can be forwarded to: the inbox, less this one. */
        val forwardTargets: StateFlow<List<Chat>> =
            chats
                .inbox()
                .map { list ->
                    list.filter { it.id != chatId }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), emptyList())

        /** Other people's reactions in this chat, for the Land and Celebrate phases. */
        val incomingReactions = reactionFeed.reactions.filter { it.chatId == chatId }

        private val limit = MutableStateFlow(MessageActions.PAGE)
        private val replyTo = MutableStateFlow<Message?>(null)
        private val moreHistory = MutableStateFlow(true)
        private val requested = mutableSetOf<AttachmentId>()

        /** Unread when the chat opened: where "New messages" goes, fixed while the chat is open. */
        private val unreadAtOpen = MutableStateFlow<Int?>(null)

        private val chat = chats.chat(chatId)
        private val account = chat.filterNotNull().flatMapLatest { accounts.account(it.accountId) }
        private val people = chat.filterNotNull().flatMapLatest { contacts.people(it.accountId) }

        val state: StateFlow<ChatUiState> =
            combine(
                combine(chat, account, ::Pair),
                limit.flatMapLatest { messages.latest(chatId, it) },
                combine(people, typing.typing.map { it[chatId].orEmpty() }, ::Pair),
                combine(pins.pinned(chatId), replyTo, moreHistory, unreadAtOpen, ::Quad),
                combine(
                    combine(
                        settingsRepo.quickReactions,
                        settingsRepo.doubleTapReaction,
                        settingsRepo.recentEmoji,
                        ::ReactionPrefs,
                    ),
                    menu.selection,
                    menu.editing,
                    menu.hidden,
                    ::Quad,
                ),
            ) { (c, a), all, (everyone, typers), extra, menuState ->
                val (prefs, picked, edit) = menuState
                val hidden = menuState.d
                val newestFirst = all.filter { it.id !in hidden }
                // "You" are whoever sent your messages here; before you have sent any, the account's own id.
                val me = all.firstOrNull { it.isOutgoing }?.senderId ?: c?.accountId?.let { SelfId.of(it) }
                val (pins, reply, more) = extra
                val unread = extra.d
                val nameMap = everyone.associate { it.id to it.displayName }
                ChatUiState(
                    loading = false,
                    chat = c,
                    account = a,
                    capabilities = a?.let { registry[it.network]?.capabilities },
                    items = chatItems(newestFirst, unread?.let { firstUnread(newestFirst, it) }),
                    names = nameMap,
                    typing = typers.mapNotNull { nameMap[it] },
                    pinned = pins,
                    phone =
                        c
                            ?.takeIf { it.kind == org.pingme.core.model.ChatKind.DIRECT }
                            ?.participants
                            ?.firstNotNullOfOrNull { id ->
                                everyone.find { it.id == id }?.phoneNumber
                            },
                    replyTo = reply,
                    moreHistory = more,
                    reactions = prefs,
                    selection = picked,
                    editing = edit,
                    me = me,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), ChatUiState())

        init {
            viewModelScope.launch {
                unreadAtOpen.value = chat.filterNotNull().first().unreadCount
                // While the chat is open, whatever arrives is read.
                chat.filterNotNull().collect { if (it.unreadCount > 0) chatActions.setRead(chatId, read = true) }
            }
        }

        /** Sends the text with whatever is waiting in the outbox; [forceSms] is "Send as SMS". */
        fun send(
            text: String,
            forceSms: Boolean = false,
        ) {
            val body = text.trim()
            val files = outbox.take()
            if (body.isEmpty() && files.isEmpty()) return
            val reply = replyTo.value
            replyTo.value = null
            stopTyping()
            viewModelScope.launch {
                val name = reply?.let { state.value.names[it.senderId] ?: youOr(it) }
                messageActions.send(chatId, body, reply, name, forceSms, files)
            }
        }

        fun reply(message: Message?) {
            replyTo.value = message
        }

        fun retry(message: Message) {
            viewModelScope.launch { messageActions.retry(message) }
        }

        /** Remembers an emoji picked from the full picker for its Recent row. */
        fun rememberEmoji(emoji: String) {
            viewModelScope.launch { settingsRepo.addRecentEmoji(emoji) }
        }

        fun unpin(message: Message) {
            viewModelScope.launch { messageActions.unpin(message.id) }
        }

        /** Near the top of what is loaded: show more from the store, and ask the network when it runs out. */
        fun loadOlder() {
            if (!moreHistory.value) return
            val shown = limit.value
            limit.update { it + MessageActions.PAGE }
            viewModelScope.launch {
                val stored = messages.latest(chatId, shown + MessageActions.PAGE).first().size
                if (stored < shown + MessageActions.PAGE) moreHistory.value = messageActions.loadOlder(chatId)
            }
        }

        /** Makes sure a picture, voice note, or file is on the phone before it is shown. */
        fun need(attachment: org.pingme.core.model.Attachment) {
            if (attachment.localPath == null && requested.add(attachment.id)) media.download(attachment.id)
        }

        private var typingJob: Job? = null

        /** The composer changed: tell the other side, and stop telling them after a pause. */
        fun typing(text: String) {
            if (text.isBlank()) return stopTyping()
            if (typingJob?.isActive != true) viewModelScope.launch { messageActions.setTyping(chatId, true) }
            typingJob?.cancel()
            typingJob =
                viewModelScope.launch {
                    delay(TYPING_PAUSE)
                    messageActions.setTyping(chatId, false)
                }
        }

        private fun stopTyping() {
            if (typingJob?.isActive == true) {
                typingJob?.cancel()
                viewModelScope.launch { messageActions.setTyping(chatId, false) }
            }
            typingJob = null
        }

        private fun youOr(message: Message) = if (message.isOutgoing) YOU else ""

        private data class Quad<A, B, C, D>(
            val a: A,
            val b: B,
            val c: C,
            val d: D,
        )

        private companion object {
            const val STOP_AFTER = 5_000L
            const val YOU = "You"
            val TYPING_PAUSE = 5.seconds
        }
    }
