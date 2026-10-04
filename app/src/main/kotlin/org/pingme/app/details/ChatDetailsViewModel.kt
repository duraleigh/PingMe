// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pingme.app.appearance.AppearanceRepository
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.model.Account
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Capabilities
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ChatOverrides
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.model.VibrationPattern
import org.pingme.core.service.ChatActions
import org.pingme.core.service.MessageActions
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatOverridesRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ChatSearchRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.PinnedMessageRepository
import org.pingme.core.store.SettingsRepository
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.ChatLook
import kotlin.time.Clock
import kotlin.time.Duration

/** Everything Chat details shows (UI_DESIGN.md 3.4). */
data class ChatDetailsState(
    val chat: Chat? = null,
    val account: Account? = null,
    val capabilities: Capabilities? = null,
    val people: List<Person> = emptyList(),
    val pinned: List<Message> = emptyList(),
    val media: List<Message> = emptyList(),
    val links: List<Message> = emptyList(),
    val files: List<Message> = emptyList(),
    val overrides: ChatOverrides? = null,
    val appearance: Appearance = Appearance(),
    val appReactions: List<String> = emptyList(),
    val self: PersonId? = null,
    /** A merged chat's members, in order of activity (UI_DESIGN.md 10.15). */
    val members: List<MemberRow> = emptyList(),
    /** One-to-one chats that could be merged with this one. */
    val candidates: List<org.pingme.app.merge.PickableChat> = emptyList(),
) {
    val look: ChatLook get() = ChatLook.fromJson(overrides?.lookJson)

    /**
     * The other person in a one-to-one chat: never you, whether the network lists you first
     * (owner, Gate G3: "Add to contacts" offered your own number) or not. You are whoever
     * sent your messages here; before that, the person the network names "You".
     */
    val person: Person? get() =
        chat
            ?.takeIf { it.kind == org.pingme.core.model.ChatKind.DIRECT }
            ?.let { people.firstOrNull { p -> p.id != self && p.displayName != YOU } ?: people.firstOrNull() }
}

/** One member of a merged chat as Chat details shows it. */
data class MemberRow(
    val chat: Chat,
    val network: org.pingme.core.model.NetworkId,
    val person: Person?,
    val photo: String?,
    /** The composer starts on this member (UI_DESIGN.md 10.15). */
    val isDefault: Boolean,
    /** What the phone and video buttons do on this member's network (UI_DESIGN.md 10.17). */
    val calls: org.pingme.core.model.CallRule? = null,
)

/** Chat details for one chat (UI_DESIGN.md 3.4, BUILD_PLAN.md P2.5). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ChatDetailsViewModel.Factory::class)
// One function per thing Chat details can do; the list reads as one (as ChatActions).
@Suppress("TooManyFunctions")
class ChatDetailsViewModel
    @AssistedInject
    constructor(
        @Assisted chatKey: String,
        private val chats: ChatRepository,
        accounts: AccountRepository,
        contacts: ContactRepository,
        private val messages: MessageRepository,
        pins: PinnedMessageRepository,
        search: ChatSearchRepository,
        private val overridesRepo: ChatOverridesRepository,
        private val appearanceRepo: AppearanceRepository,
        settings: SettingsRepository,
        registry: ConnectorRegistry,
        private val chatActions: ChatActions,
        private val messageActions: MessageActions,
        private val clock: Clock,
        private val requests: org.pingme.app.chat.ChatRequests,
        private val merges: org.pingme.core.service.merge.Merges,
        mergeRepo: org.pingme.core.store.MergeRepository,
    ) : ViewModel() {
        /** Takes the chat id as text: Hilt cannot generate factories for value classes. */
        @AssistedFactory
        interface Factory {
            fun create(chatKey: String): ChatDetailsViewModel
        }

        val chatId = ChatId(chatKey)
        private val problems = Channel<String>(Channel.BUFFERED)

        /** A network's refusal, for the snackbar. */
        val notices = problems.receiveAsFlow()

        private val chat = chats.chat(chatId)
        private val account = chat.filterNotNull().flatMapLatest { accounts.account(it.accountId) }

        // Everyone any chat lists: a merged chat's people span accounts (UI_DESIGN.md 10.15).
        private val people = contacts.inChats()

        private val merging =
            combine(mergeRepo.observeMembers(chatId), chats.all(), accounts.accounts(), ::Triple)

        private val media =
            combine(
                search.ofKinds(chatId, setOf(MessageKind.IMAGE, MessageKind.VIDEO, MessageKind.GIF), PREVIEW),
                search.withLinks(chatId, PREVIEW),
                search.ofKinds(
                    chatId,
                    setOf(MessageKind.FILE, MessageKind.VOICE, MessageKind.CONTACT, MessageKind.LOCATION),
                    PREVIEW,
                ),
                ::Triple,
            )

        val state: StateFlow<ChatDetailsState> =
            combine(
                combine(chat, account, merging, ::Triple),
                combine(people, pins.pinned(chatId), ::Pair),
                media,
                overridesRepo.overrides(chatId),
                combine(appearanceRepo.appearance, settings.quickReactions, ::Pair),
            ) { (c, a, merge), (everyone, pinned), (pictures, links, files), overrides, (look, reactions) ->
                val members = c?.participants.orEmpty().mapNotNull { everyone[it] }
                val (memberChats, allChats, accountList) = merge
                val networkOf = accountList.associate { it.id to it.network }
                ChatDetailsState(
                    chat = c,
                    account = a,
                    capabilities = a?.let { registry[it.network]?.capabilities },
                    people = members,
                    pinned = pinned,
                    media = pictures,
                    links = links,
                    files = files,
                    overrides = overrides,
                    appearance = look,
                    appReactions = reactions,
                    self = messages.selfIn(chatId),
                    members =
                        memberChats.map { m ->
                            val person =
                                m.participants
                                    .mapNotNull {
                                        everyone[it]
                                    }.firstOrNull { it.displayName != YOU }
                            val network = networkOf[m.accountId] ?: org.pingme.core.model.NetworkId.DEMO
                            MemberRow(
                                m,
                                network,
                                person,
                                org.pingme.app.inbox
                                    .photoFor(m, everyone),
                                isDefault = m.accountId == c?.defaultSendAccount,
                                calls = registry[network]?.capabilities?.calls,
                            )
                        },
                    candidates =
                        allChats
                            .filter { it.id != chatId && it.kind == ChatKind.DIRECT && it.mergedInto == null }
                            .filter { other -> allChats.none { it.mergedInto == other.id } }
                            .map { other ->
                                org.pingme.app.merge.PickableChat
                                    .of(
                                        other,
                                        networkOf[other.accountId] ?: org.pingme.core.model.NetworkId.DEMO,
                                        everyone,
                                    )
                            },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), ChatDetailsState())

        /** Mutes for [length], or for good when it is null; unmutes with [unmute]. */
        fun mute(length: Duration?) = act { chatActions.muteUntil(chatId, length?.let { clock.now() + it }) }

        fun unmute() = act { chatActions.setMuted(chatId, false) }

        /** A new sound (null for the default, [ChatOverrides.SILENT] for none) and vibration (UI_DESIGN.md 6.1). */
        fun setNotification(
            sound: String?,
            vibration: VibrationPattern?,
        ) = act { overridesRepo.setNotification(chatId, sound, vibration) }

        fun setLook(look: ChatLook) = act { overridesRepo.update(chatId) { it.copy(lookJson = look.toJson()) } }

        /** Copies a picked picture in and makes it this chat's wallpaper. */
        fun importWallpaper(
            source: Uri,
            blurred: Boolean,
        ) = act {
            val file = appearanceRepo.importWallpaper(source)
            val look = state.value.look
            overridesRepo.update(chatId) {
                it.copy(
                    lookJson =
                        look
                            .copy(
                                wallpaper =
                                    org.pingme.core.ui.theme.ChatWallpaper
                                        .Image(file.absolutePath, blurred),
                            ).toJson(),
                )
            }
        }

        /** This chat's quick reactions; null goes back to the app's set. */
        fun setReactions(set: List<String>?) = act { overridesRepo.update(chatId) { it.copy(quickReactions = set) } }

        fun setObscured(on: Boolean) = act { chatActions.setObscured(chatId, on) }

        fun setLowPriority(on: Boolean) = act { chatActions.setLowPriority(chatId, on) }

        fun setPinned(on: Boolean) = act { chatActions.setPinned(chatId, on) }

        fun setArchived(on: Boolean) = act { chatActions.setArchived(chatId, on) }

        fun rename(name: String) = act { chatActions.rename(chatId, name) }

        fun setAvatar(source: AvatarSource) = act { chatActions.setAvatarSource(chatId, source) }

        fun moveTo(folder: ChatFolder) = act { chatActions.moveFolder(chatId, folder) }

        fun unpin(message: Message) = act { messageActions.unpin(message.id) }

        /** Asks the chat underneath to open search on [type] when the user goes back to it. */
        fun askSearch(type: org.pingme.app.chat.search.SearchType) =
            requests.ask(
                org.pingme.app.chat.ChatRequest
                    .Search(chatId, type),
            )

        /** Asks the chat underneath to show [message]. */
        fun askJump(message: Message) =
            requests.ask(
                org.pingme.app.chat.ChatRequest
                    .Jump(chatId, message.id),
            )

        /** Blocks the person on the network; the chat leaves PingMe. */
        fun block(then: () -> Unit) = act(then) { chatActions.block(chatId) }

        /** Deletes the chat from this phone; the network keeps its copy. */
        fun delete(then: () -> Unit) = act(then) { chatActions.delete(chatId) }

        /** Merges this chat with [others]; [then] gets the merged chat to open (UI_DESIGN.md 10.15). */
        fun mergeWith(
            others: List<ChatId>,
            then: (ChatId) -> Unit,
        ) {
            viewModelScope.launch {
                runCatching { merges.merge(listOf(chatId) + others) }
                    .onSuccess { then(it) }
                    .onFailure { problems.trySend(it.message ?: it.javaClass.simpleName) }
            }
        }

        /** Adds [others] to this merged chat. */
        fun addMembers(others: List<ChatId>) = act { merges.merge(others + chatId, into = chatId) }

        /** Takes [member] out; if that dissolves the merged chat, [gone] leaves the screen. */
        fun split(
            member: ChatId,
            gone: () -> Unit,
        ) {
            viewModelScope.launch {
                runCatching { merges.split(member) }
                    .onSuccess { if (chats.get(chatId) == null) gone() }
                    .onFailure { problems.trySend(it.message ?: it.javaClass.simpleName) }
            }
        }

        fun setDefault(account: org.pingme.core.model.AccountId) = act { merges.setDefault(chatId, account) }

        // Runs a change; a network's refusal goes to the snackbar instead of crashing the screen.
        private fun act(
            then: () -> Unit = {},
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch {
                runCatching { block() }
                    .onSuccess { then() }
                    .onFailure { problems.trySend(it.message ?: it.javaClass.simpleName) }
            }
        }

        private companion object {
            const val STOP_AFTER = 5_000L

            /** How many of each kind Chat details shows before "See all". */
            const val PREVIEW = 12
        }
    }

/** What every connector names your own entry among a chat's people. */
private const val YOU = "You"
