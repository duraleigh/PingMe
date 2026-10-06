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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pingme.app.chat.voice.VoiceNotes
import org.pingme.app.chat.voice.VoiceRecorder
import org.pingme.app.chat.voice.asAttachment
import org.pingme.app.inbox.displayName
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.connector.accountId
import org.pingme.core.model.Account
import org.pingme.core.model.AttachmentId
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.Capabilities
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.MediaRule
import org.pingme.core.model.Message
import org.pingme.core.model.NetworkId
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
import kotlin.time.Clock
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
    /** The other person's Instagram profile page, in an Instagram chat (owner, 2026-10-05). */
    val profileUrl: String? = null,
    /** The contact's or network's photo for the header (UI_DESIGN.md 10.18). */
    val photo: String? = null,
    /** A group's members for the header's composite avatar (owner, 2026-10-03). */
    val faces: List<org.pingme.core.ui.components.Face> = emptyList(),
    /** A merged chat's members, one chip each (UI_DESIGN.md 10.15); empty for an ordinary chat. */
    val members: List<MemberChip> = emptyList(),
    /** Which member's bubbles are shown: an account, or null for all (owner, Phase 7). */
    val filter: org.pingme.core.model.AccountId? = null,
    /** The member the composer sends through; null outside a merged chat. */
    val sendVia: ChatId? = null,
    /** The network the next message goes out on: the chosen member's, or the chat's own. */
    val sendNetwork: NetworkId? = null,
    /** Each member chat's network, for bubble colours and the badge beside the ticks. */
    val networkOf: Map<ChatId, NetworkId> = emptyMap(),
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

/** One member of a merged chat as the header dropdown and the composer chips show it. */
data class MemberChip(
    val chatId: ChatId,
    val accountId: org.pingme.core.model.AccountId,
    val network: NetworkId,
    /** The account's own name, for two accounts on one network. */
    val label: String,
    /** Red-lined when the account is not connected (UI_DESIGN.md 10.15). */
    val connected: Boolean,
    val isDefault: Boolean,
)

/** A message held back by the MMS size warning (UI_DESIGN.md 5.5, 5.6). */
data class HeldBack(
    val body: String,
    val files: List<OutgoingAttachment>,
    val forceSms: Boolean,
    /** An online GIF that has a smaller size to offer instead ("Shrink"). */
    val shrink: org.pingme.app.chat.gif.Gif? = null,
) {
    val bytes: Long get() = files.sumOf { java.io.File(it.localPath).length() }
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
// One function per thing the chat screen can ask for; the list reads as one (as ChatActions).
@Suppress("TooManyFunctions")
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
        recorder: VoiceRecorder,
        dictationRecorder: VoiceRecorder,
        groq: org.pingme.app.chat.voice.GroqTranscriber,
        gifStore: org.pingme.app.chat.gif.GifStore,
        private val searchRepo: org.pingme.core.store.ChatSearchRepository,
        requests: ChatRequests,
        overridesRepo: org.pingme.core.store.ChatOverridesRepository,
        transcriber: org.pingme.app.chat.voice.VoiceTranscriber,
        private val presence: org.pingme.core.service.notify.ChatPresence,
        /** The ClearURLs rules, for links shown cleaned (UI_DESIGN.md 10.11). */
        val links: org.pingme.core.service.links.CleanLinks,
        private val mergeRepo: org.pingme.core.store.MergeRepository,
    ) : ViewModel() {
        private val onScreen = MutableStateFlow(false)

        /**
         * The chat is (or is no longer) the one on screen: its messages notify only while it is
         * not, and count as read only while it is (owner, Gate G3: a chat left behind in the
         * back stack kept marking new messages read).
         */
        fun visible(on: Boolean) {
            onScreen.value = on
            if (on) {
                presence.visible = chatId
                // The view model outlives one visit: the opening rule runs on every showing,
                // and nothing marks the chat read until it has decided.
                decided.value = false
                viewModelScope.launch {
                    openOn()
                    // Opening a chat tells its network it is read, unread here or not.
                    runCatching { chatActions.tellNetworkRead(chatId) }
                }
            } else if (presence.visible == chatId) {
                presence.visible = null
            }
        }

        /**
         * A merged chat opens, header, bubbles, and box alike, on the one network its unread
         * messages came from; with none, or several, on its default network. "All" stays a
         * choice in the header menu (owner, 2026-10-03).
         */
        private suspend fun openOn() {
            try {
                if (!isMergedId(chatId)) return
                // Asked of the store directly: on a first visit the member flow has not
                // loaded yet, and waiting for it let the read marking zero the counts first,
                // so the chat opened on its default network (owner, 2026-10-04).
                val list = mergeRepo.members(chatId)
                if (list.isEmpty()) return
                val unreadAccounts = list.filter { it.unreadCount > 0 }.map { it.accountId }.distinct()
                val fallback = chats.get(chatId)?.defaultSendAccount ?: list.first().accountId
                filter.value = unreadAccounts.singleOrNull() ?: fallback
                chosenVia.value = null
            } finally {
                decided.value = true
            }
        }

        /** True once the opening rule has run for this showing; read marking waits for it. */
        private val decided = MutableStateFlow(false)

        /** Voice-note transcripts, when Settings turns them on (UI_DESIGN.md 5.6). */
        val transcripts =
            org.pingme.app.chat.voice
                .Transcripts(transcriber, viewModelScope)

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

        /** Hold to record, slide to cancel, slide up to lock (UI_DESIGN.md 5.6). */
        val voice =
            VoiceNotes(
                viewModelScope,
                recorder,
                Clock.System,
                { mediaRule(AttachmentKind.VOICE) != MediaRule.NATIVE },
            ) {
                dispatch("", listOf(it.asAttachment()), forceSms = false)
            }

        /** Settings that change how the chat looks and plays: GIF autoplay, special emoji (P2.6). */
        val appSettings =
            settingsRepo.app.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                org.pingme.core.model
                    .AppSettings(),
            )

        /** Volume down held: dictation into the box through Groq with the owner's key (UI_DESIGN.md 5.6). */
        val dictation =
            org.pingme.app.chat.voice.Dictation(
                viewModelScope,
                dictationRecorder,
                Clock.System,
                {
                    appSettings.value.media.groqKey
                        .trim()
                },
                groq::transcribe,
            )

        /** A merged chat's members (UI_DESIGN.md 10.15); empty for an ordinary chat. */
        private val members =
            mergeRepo
                .observeMembers(chatId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** The chats whose messages make this screen: the members, or the chat itself. */
        private val sources: StateFlow<List<ChatId>> =
            members
                .map { list -> list.map { it.id }.ifEmpty { listOf(chatId) } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(chatId))

        /** Bubbles shown: one member's account, or all (owner, Phase 7). */
        private val filter = MutableStateFlow<org.pingme.core.model.AccountId?>(null)

        /** The member the composer sends through, when the user picked one by hand. */
        private val chosenVia = MutableStateFlow<ChatId?>(null)

        /** Search in this chat (UI_DESIGN.md 10.14), across a merged chat's members. */
        val search =
            org.pingme.app.chat.search
                .ChatSearch(viewModelScope, sources, messages, searchRepo)

        /** Scrolling to a search result or a date, loading history back to it first. */
        val jumps =
            org.pingme.app.chat.search.Jumps(viewModelScope, sources, messages, searchRepo) { needed ->
                limit.update { maxOf(it, needed) }
            }

        /** GIF search, Trending, and favourites (UI_DESIGN.md 5.5). */
        val gifs =
            org.pingme.app.chat.gif
                .GifSearch(viewModelScope, gifStore, settingsRepo.app.map { it.media.gifSearch })

        private val tooBig = MutableStateFlow<HeldBack?>(null)

        /** A GIF or voice note over the carrier's MMS limit, waiting for the user to decide (UI_DESIGN.md 5.5). */
        val heldBack: StateFlow<HeldBack?> = tooBig.asStateFlow()

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

        /** This chat's own settings from Chat details: its look and its quick reactions. */
        val overrides =
            overridesRepo
                .overrides(chatId)
                .stateIn(
                    viewModelScope,
                    SharingStarted.Eagerly,
                    org.pingme.core.model
                        .ChatOverrides(chatId),
                )

        private val chat = chats.chat(chatId)
        private val account = chat.filterNotNull().flatMapLatest { accounts.account(it.accountId) }

        // Everyone any chat lists: a merged chat's people span accounts (UI_DESIGN.md 10.15).
        private val people = contacts.inChats().map { it.values.toList() }

        /** Members with their accounts, and what the composer sends through (UI_DESIGN.md 10.15). */
        private val merged =
            combine(chat, members, accounts.accounts(), filter, chosenVia) { c, list, all, shown, chosen ->
                val byAccount = all.associateBy { it.id }
                val twins = all.groupBy { it.network }.filterValues { it.size > 1 }.keys
                val chips =
                    list.map { m ->
                        val a = byAccount[m.accountId]
                        MemberChip(
                            m.id,
                            m.accountId,
                            a?.network ?: NetworkId.DEMO,
                            a?.let { if (it.network in twins) it.displayName else it.network.displayName }.orEmpty(),
                            a?.state == org.pingme.core.model.ConnectionState.Connected,
                            isDefault = m.accountId == c?.defaultSendAccount,
                        )
                    }
                val via =
                    chips.firstOrNull { it.chatId == chosen }
                        ?: shown?.let { chips.firstOrNull { chip -> chip.accountId == it } }
                        ?: chips.firstOrNull { it.isDefault }
                        ?: chips.firstOrNull()
                Merged(chips, shown, via?.chatId, chips.associate { it.chatId to it.network })
            }

        val state: StateFlow<ChatUiState> =
            combine(
                combine(chat, account, merged, ::Triple),
                combine(limit, sources) { n, ids -> n to ids }.flatMapLatest { (n, ids) ->
                    if (ids.size == 1) messages.latest(ids.single(), n) else messages.latestIn(ids, n)
                },
                combine(people, typing.typing.map { t -> sources.value.flatMap { t[it].orEmpty() }.toSet() }, ::Pair),
                combine(sources.flatMapLatest { pins.pinnedIn(it) }, replyTo, moreHistory, unreadAtOpen, ::Quad),
                combine(
                    combine(
                        // This chat's own quick reactions from Chat details win over the app's (UI_DESIGN.md 3.4).
                        combine(settingsRepo.quickReactions, overrides) { app, own -> own.quickReactions ?: app },
                        settingsRepo.doubleTapReaction,
                        settingsRepo.recentEmoji,
                        ::ReactionPrefs,
                    ),
                    menu.selection,
                    menu.editing,
                    menu.hidden,
                    ::Quad,
                ),
            ) { (c, a, m), all, (everyone, typers), extra, menuState ->
                val (prefs, picked, edit) = menuState
                val hidden = menuState.d
                // In a merged chat the dropdown can narrow the bubbles to one network (owner, Phase 7).
                val newestFirst =
                    all.filter {
                        it.id !in hidden && (m.filter == null || it.chatId.accountId == m.filter)
                    }
                // "You" are whoever sent your messages here; before you have sent any, the account's own id.
                val me = all.firstOrNull { it.isOutgoing }?.senderId ?: c?.accountId?.let { SelfId.of(it) }
                val (pins, reply, more) = extra
                val unread = extra.d
                val nameMap = everyone.associate { it.id to it.name }
                val sending = m.chips.firstOrNull { it.chatId == m.sendVia }
                val sendNetwork = sending?.network ?: a?.network
                ChatUiState(
                    loading = false,
                    chat = c,
                    account = a,
                    capabilities = sendNetwork?.let { registry[it]?.capabilities },
                    members = m.chips,
                    filter = m.filter,
                    sendVia = m.sendVia,
                    sendNetwork = sendNetwork,
                    networkOf = m.networkOf,
                    items =
                        chatItems(newestFirst, unread?.let { firstUnread(newestFirst, it) }) {
                            messageActions.shownAs(it.id).value
                        },
                    names = nameMap,
                    typing = typers.mapNotNull { nameMap[it] },
                    pinned = pins,
                    phone = c?.let { callNumber(it.kind, it.participants, sending?.accountId, me, everyone) },
                    profileUrl =
                        c?.let {
                            instagramProfileUrl(
                                it.kind,
                                it.participants,
                                sendNetwork,
                                sending?.accountId ?: a?.id,
                                me,
                                everyone,
                            )
                        },
                    photo =
                        c?.let {
                            org.pingme.app.inbox
                                .photoFor(it, everyone.associateBy { p -> p.id })
                        },
                    faces =
                        c
                            ?.let {
                                org.pingme.app.inbox
                                    .facesFor(it, everyone.associateBy { p -> p.id })
                            }.orEmpty(),
                    replyTo = reply,
                    moreHistory = more,
                    reactions = prefs,
                    selection = picked,
                    editing = edit,
                    me = me,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), ChatUiState())

        init {
            // Chat details can ask for search or a jump to a pinned message.
            viewModelScope.launch {
                requests.requests.collect {
                    when (val request = requests.take(chatId)) {
                        is ChatRequest.Search -> {
                            search.open()
                            search.type(request.type)
                        }

                        is ChatRequest.Jump -> {
                            messages.get(request.messageId)?.let(jumps::to)
                        }

                        null -> {
                            Unit
                        }
                    }
                }
            }
            viewModelScope.launch {
                unreadAtOpen.value = chat.filterNotNull().first().unreadCount
                // While the chat is on screen, whatever arrives is read and its notification goes.
                combine(chat.filterNotNull(), onScreen, ::Pair).collect { (c, shown) ->
                    if (!shown) return@collect
                    chatActions.opened(chatId)
                    if (c.unreadCount > 0) {
                        decided.first { it }
                        chatActions.setRead(chatId, read = true)
                    }
                }
            }
        }

        /** The member chat a send, a typing notice, or a history request goes to. */
        private val target: ChatId get() = state.value.sendVia ?: chatId

        /** The header dropdown: one member's bubbles, or all; the composer follows (owner, Phase 7). */
        fun setFilter(account: org.pingme.core.model.AccountId?) {
            filter.value = account
            chosenVia.value = null
        }

        /** The box badge moves the whole chat to this member's network, as the header menu does (owner). */
        fun sendVia(member: ChatId) {
            val account =
                state.value.members
                    .firstOrNull { it.chatId == member }
                    ?.accountId
            if (account != null) filter.value = account
            chosenVia.value = member
        }

        /** Sends the text with whatever is waiting in the outbox; [forceSms] is "Send as SMS". */
        fun send(
            text: String,
            forceSms: Boolean = false,
        ) {
            dispatch(text.trim(), outbox.take(), forceSms)
        }

        /** Keeps the text and whatever waits in the outbox to send at [at] (UI_DESIGN.md 10.13). */
        fun schedule(
            text: String,
            at: kotlin.time.Instant,
        ) {
            val body = text.trim()
            val files = outbox.take()
            if (body.isEmpty() && files.isEmpty()) return
            val reply = replyTo.value
            replyTo.value = null
            stopTyping()
            viewModelScope.launch {
                val name = reply?.let { state.value.names[it.senderId] ?: youOr(it) }
                messageActions.schedule(target, body, at, reply, name, files)
            }
        }

        /** The user's answer to the MMS size warning: send it as it is, or not at all. */
        fun answerHeldBack(send: Boolean) {
            val held = tooBig.value ?: return
            tooBig.value = null
            if (send) go(held.body, held.files, held.forceSms)
        }

        /** "Shrink": sends the GIF's smaller size instead. */
        fun shrinkHeldBack() {
            val held = tooBig.value ?: return
            val gif = held.shrink ?: return
            tooBig.value = null
            viewModelScope.launch {
                gifs.store
                    .forSending(
                        gif,
                        small = true,
                    )?.let { go("", listOf(it), held.forceSms) }
            }
        }

        /** Sends a GIF picked from search or Trending (UI_DESIGN.md 5.5). */
        fun sendGif(gif: org.pingme.app.chat.gif.Gif) {
            viewModelScope.launch {
                gifs.store
                    .forSending(
                        gif,
                    )?.let { dispatch("", listOf(it), forceSms = false, shrink = gif) }
            }
        }

        /** Sends one of the user's favourite GIFs. */
        fun sendFavourite(file: java.io.File) {
            viewModelScope.launch {
                gifs.store
                    .favouriteForSending(
                        file,
                    )?.let { dispatch("", listOf(it), forceSms = false) }
            }
        }

        private fun dispatch(
            body: String,
            files: List<OutgoingAttachment>,
            forceSms: Boolean,
            shrink: org.pingme.app.chat.gif.Gif? = null,
        ) {
            if (body.isEmpty() && files.isEmpty()) return
            if (overMmsLimit(files, forceSms)) {
                tooBig.value = HeldBack(body, files, forceSms, shrink?.takeIf { it.small != null })
                return
            }
            go(body, files, forceSms)
        }

        private fun go(
            body: String,
            files: List<OutgoingAttachment>,
            forceSms: Boolean,
        ) {
            val reply = replyTo.value
            replyTo.value = null
            stopTyping()
            viewModelScope.launch {
                val name = reply?.let { state.value.names[it.senderId] ?: youOr(it) }
                messageActions.send(target, body, reply, name, forceSms, files)
            }
        }

        // GIFs and voice notes that will go as MMS are checked against the usual carrier limit.
        private fun overMmsLimit(
            files: List<OutgoingAttachment>,
            forceSms: Boolean,
        ): Boolean {
            val limited =
                files.filter { file ->
                    val rule = mediaRule(file.kind) ?: return@filter false
                    rule == MediaRule.MMS_SIZE_LIMITED || (forceSms && rule == MediaRule.NATIVE)
                }
            return limited.isNotEmpty() && limited.sumOf { java.io.File(it.localPath).length() } > MMS_LIMIT
        }

        private fun mediaRule(kind: AttachmentKind): MediaRule? {
            val capabilities = state.value.capabilities ?: return null
            return when (kind) {
                AttachmentKind.GIF -> capabilities.gif
                AttachmentKind.VOICE -> capabilities.voiceNote
                else -> null
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
                val ids = sources.value
                val stored = messages.latestIn(ids, shown + MessageActions.PAGE).first().size
                // A merged chat asks every member's network; more remains while any has more.
                if (stored < shown + MessageActions.PAGE) {
                    moreHistory.value = ids.map { messageActions.loadOlder(it) }.any { it }
                }
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
            val to = target
            if (typingJob?.isActive != true) viewModelScope.launch { messageActions.setTyping(to, true) }
            typingJob?.cancel()
            typingJob =
                viewModelScope.launch {
                    delay(TYPING_PAUSE)
                    messageActions.setTyping(to, false)
                }
        }

        private fun stopTyping() {
            if (typingJob?.isActive == true) {
                typingJob?.cancel()
                val to = target
                viewModelScope.launch { messageActions.setTyping(to, false) }
            }
            typingJob = null
        }

        private fun youOr(message: Message) = if (message.isOutgoing) YOU else ""

        private data class Merged(
            val chips: List<MemberChip>,
            val filter: org.pingme.core.model.AccountId?,
            val sendVia: ChatId?,
            val networkOf: Map<ChatId, NetworkId>,
        )

        private fun isMergedId(id: ChatId) =
            org.pingme.core.service.merge.Merges
                .isMergedId(id)

        private data class Quad<A, B, C, D>(
            val a: A,
            val b: B,
            val c: C,
            val d: D,
        )

        private companion object {
            const val STOP_AFTER = 5_000L

            /** The size most carriers cap an MMS at. */
            const val MMS_LIMIT = 1_000_000L
            const val YOU = "You"
            val TYPING_PAUSE = 5.seconds
        }
    }

/**
 * The number the call icons dial in a one-to-one chat: the other person's, never the owner's
 * own. Google Messages lists the owner among a chat's participants, as "You", and the first
 * participant with a number was the owner (owner, 2026-10-06: "it calls my phone number!!!").
 */
internal fun callNumber(
    kind: org.pingme.core.model.ChatKind,
    participants: List<PersonId>,
    accountId: org.pingme.core.model.AccountId?,
    me: PersonId?,
    everyone: List<org.pingme.core.model.Person>,
): String? {
    if (kind != org.pingme.core.model.ChatKind.DIRECT) return null
    return participants
        .filter { (accountId == null || it.accountId == accountId) && it != me }
        .mapNotNull { id -> everyone.find { it.id == id } }
        .filterNot { it.displayName == YOU }
        .firstNotNullOfOrNull { it.phoneNumber }
}

private const val YOU = "You"

/**
 * The Instagram profile page of the one other person in a direct Instagram chat, or null:
 * tapping the avatar in the header opens it (owner, 2026-10-05). A person known only by a
 * numeric id has no page to open.
 */

internal fun instagramProfileUrl(
    kind: org.pingme.core.model.ChatKind,
    participants: List<PersonId>,
    network: NetworkId?,
    accountId: org.pingme.core.model.AccountId?,
    me: PersonId?,
    everyone: List<org.pingme.core.model.Person>,
): String? {
    if (kind != org.pingme.core.model.ChatKind.DIRECT || network != NetworkId.INSTAGRAM ||
        accountId == null
    ) {
        return null
    }
    val others = participants.filter { it.accountId == accountId && it != me }
    val handle =
        others
            .firstNotNullOfOrNull { id -> everyone.find { it.id == id }?.networkHandle }
            ?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
    if (handle == null) noteNoProfile(participants, me, others, everyone)
    return handle?.let { "https://www.instagram.com/$it/" }
}

private val notedProfiles = java.util.Collections.synchronizedSet(HashSet<String>())

/** Why an Instagram chat has no profile page, once per chat, for the diagnostic file (owner, 2026-10-05). */
private fun noteNoProfile(
    participants: List<PersonId>,
    me: PersonId?,
    others: List<PersonId>,
    everyone: List<org.pingme.core.model.Person>,
) {
    val key = participants.joinToString { it.value }
    if (!notedProfiles.add(key)) return
    val handles = others.map { id -> "${id.value}=${everyone.find { it.id == id }?.networkHandle}" }
    org.pingme.core.connector.Diag
        .note("PingMeChat", "No Instagram profile: me=${me?.value} participants=$key others=$handles")
}
