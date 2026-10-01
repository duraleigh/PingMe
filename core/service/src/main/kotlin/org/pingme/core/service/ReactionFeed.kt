// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.pingme.core.model.ChatId
import org.pingme.core.model.MessageId
import javax.inject.Inject
import javax.inject.Singleton

/** Someone else reacted to [messageId] in [chatId] with [emoji]. */
data class IncomingReaction(
    val chatId: ChatId,
    val emoji: String,
    val messageId: MessageId? = null,
)

/**
 * Reactions from other people as they arrive, for the inbox's flippy rows
 * (UI_DESIGN.md 10.8). Live only: never stored, never replayed to a late listener.
 */
@Singleton
class ReactionFeed
    @Inject
    constructor() {
        private val flow = MutableSharedFlow<IncomingReaction>(extraBufferCapacity = BUFFER)

        val reactions: SharedFlow<IncomingReaction> = flow.asSharedFlow()

        fun emit(reaction: IncomingReaction) {
            flow.tryEmit(reaction)
        }

        private companion object {
            const val BUFFER = 16
        }
    }
