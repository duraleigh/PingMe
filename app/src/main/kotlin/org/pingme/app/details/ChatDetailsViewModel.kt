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

/** Chat details for one chat (UI_DESIGN.md 3.4, BUILD_PLAN.md P2.5). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ChatDetailsViewModel.Factory::class)
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

        // The chat's people in the order the chat lists them; group members include everyone else.
        private val people =
            chat.filterNotNull().flatMapLatest { c ->
                contacts.people(c.accountId)
            }

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
                combine(chat, account, ::Pair),
                combine(people, pins.pinned(chatId), ::Pair),
                media,
                overridesRepo.overrides(chatId),
                combine(appearanceRepo.appearance, settings.quickReactions, ::Pair),
            ) { (c, a), (everyone, pinned), (pictures, links, files), overrides, (look, reactions) ->
                val members = c?.participants.orEmpty().mapNotNull { id -> everyone.firstOrNull { it.id == id } }
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
