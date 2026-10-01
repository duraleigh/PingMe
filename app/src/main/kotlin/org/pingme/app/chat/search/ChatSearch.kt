// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.search

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.pingme.app.R
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.model.PersonId
import org.pingme.core.store.ChatSearchRepository
import org.pingme.core.store.MessageRepository

/** Search in chat's type chips (UI_DESIGN.md 10.14). Media types show as a grid. */
enum class SearchType(
    @param:StringRes val label: Int,
    val kinds: Set<MessageKind> = emptySet(),
    val grid: Boolean = false,
) {
    TEXT(R.string.search_text),
    PHOTOS(R.string.search_photos, setOf(MessageKind.IMAGE, MessageKind.STICKER), grid = true),
    VIDEOS(R.string.search_videos, setOf(MessageKind.VIDEO), grid = true),
    LINKS(R.string.search_links),
    FILES(R.string.search_files, setOf(MessageKind.FILE, MessageKind.CONTACT, MessageKind.LOCATION)),
    VOICE(R.string.search_voice, setOf(MessageKind.VOICE)),
    GIFS(R.string.search_gifs, setOf(MessageKind.GIF), grid = true),
}

/** What search in chat is showing; [open] false means the normal header is back. */
data class ChatSearchState(
    val open: Boolean = false,
    val query: String = "",
    val type: SearchType = SearchType.TEXT,
    val sender: PersonId? = null,
    val results: List<Message> = emptyList(),
)

/**
 * Search in one chat (UI_DESIGN.md 10.14): full text over the local index, or one type of
 * message, optionally from one sender in a group.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearch(
    scope: CoroutineScope,
    private val chatId: ChatId,
    messages: MessageRepository,
    search: ChatSearchRepository,
) {
    private val asked = MutableStateFlow(ChatSearchState())

    val state: StateFlow<ChatSearchState> =
        asked
            .flatMapLatest { ask ->
                val found =
                    when {
                        !ask.open -> {
                            flowOf(emptyList())
                        }

                        // With a sender picked and no words, everything that person sent.
                        ask.type == SearchType.TEXT && ask.query.isBlank() && ask.sender != null -> {
                            search.from(
                                chatId,
                                ask.sender,
                            )
                        }

                        ask.type == SearchType.TEXT -> {
                            messages.search(ask.query, chatId)
                        }

                        ask.type == SearchType.LINKS -> {
                            search.withLinks(chatId)
                        }

                        else -> {
                            search.ofKinds(chatId, ask.type.kinds)
                        }
                    }
                combine(flowOf(ask), found) { a, list ->
                    a.copy(
                        results =
                            list
                                .filter {
                                    a.sender == null || it.senderId == a.sender
                                }.filterNot { it.deletedForEveryone },
                    )
                }
            }.stateIn(scope, SharingStarted.Eagerly, ChatSearchState())

    fun open() = asked.update { ChatSearchState(open = true) }

    fun close() = asked.update { ChatSearchState() }

    fun query(text: String) = asked.update { it.copy(query = text) }

    fun type(type: SearchType) = asked.update { it.copy(type = type) }

    /** Only messages from [person]; null for anyone. */
    fun sender(person: PersonId?) = asked.update { it.copy(sender = person) }
}

/** Whether the panel has anything to show yet: typed words, or a type chip other than Text. */
val ChatSearchState.showing get() = open && (type != SearchType.TEXT || query.isNotBlank() || sender != null)

/** Search in chat for the screen: its state, and what picking a result or a date does. */
class SearchHooks(
    val search: ChatSearch? = null,
    val state: ChatSearchState = ChatSearchState(),
    val onOpen: (Message) -> Unit = {},
    val onDate: (kotlin.time.Instant) -> Unit = {},
    /** A message waiting to be scrolled to once it is loaded. */
    val jump: org.pingme.core.model.MessageId? = null,
    val onJumped: () -> Unit = {},
)

/** Who can be picked in the sender filter: everyone in a group, by name. */
class SearchPeople(
    val group: Boolean,
    val names: Map<PersonId, String>,
)
