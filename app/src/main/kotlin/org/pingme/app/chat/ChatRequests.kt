// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pingme.app.chat.search.SearchType
import org.pingme.core.model.ChatId
import org.pingme.core.model.MessageId
import javax.inject.Inject
import javax.inject.Singleton

/** Something another screen asks an open chat to do when the user goes back to it. */
sealed interface ChatRequest {
    val chatId: ChatId

    /** Open search in chat on [type] (Chat details' Search and "See all"). */
    data class Search(
        override val chatId: ChatId,
        val type: SearchType,
    ) : ChatRequest

    /** Scroll to a message (a pinned message tapped in Chat details). */
    data class Jump(
        override val chatId: ChatId,
        val messageId: MessageId,
    ) : ChatRequest
}

/** Hands requests from Chat details to the chat screen underneath it. */
@Singleton
class ChatRequests
    @Inject
    constructor() {
        private val pending = MutableStateFlow<ChatRequest?>(null)

        val requests: StateFlow<ChatRequest?> = pending.asStateFlow()

        fun ask(request: ChatRequest) {
            pending.value = request
        }

        /** The waiting request for [chatId], taken so it runs once. */
        fun take(chatId: ChatId): ChatRequest? =
            pending.value?.takeIf { it.chatId == chatId }?.also { pending.compareAndSet(it, null) }
    }
