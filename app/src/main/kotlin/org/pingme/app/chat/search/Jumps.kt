// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.store.ChatSearchRepository
import org.pingme.core.store.MessageRepository
import kotlin.time.Instant

/**
 * Showing a message in place, however far back it is (UI_DESIGN.md 10.14): the chat loads
 * history back to it through [reach], then the list scrolls there and pulses it.
 */
class Jumps(
    private val scope: CoroutineScope,
    private val chatIds: StateFlow<List<ChatId>>,
    private val messages: MessageRepository,
    private val search: ChatSearchRepository,
    private val reach: (Int) -> Unit,
) {
    private val waiting = MutableStateFlow<MessageId?>(null)

    /** A message the list should scroll to once it is loaded. */
    val request: StateFlow<MessageId?> = waiting.asStateFlow()

    fun to(message: Message) {
        scope.launch {
            reach(search.countNewerIn(chatIds.value, message.sentAt) + PAGE)
            waiting.value = message.id
        }
    }

    /** The first message on or after [day]. */
    fun toDate(day: Instant) {
        scope.launch {
            // The first message on or after the day in any member chat.
            val first = chatIds.value.mapNotNull { search.firstFrom(it, day) }.mapNotNull { messages.get(it) }
            first.minByOrNull { it.sentAt }?.let(::to)
        }
    }

    fun done() {
        waiting.value = null
    }

    private companion object {
        /** Messages loaded past the target, so it does not sit at the very end of the list. */
        const val PAGE = 50
    }
}
