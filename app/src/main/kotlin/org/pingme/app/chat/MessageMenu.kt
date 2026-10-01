// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.ReactionRule
import org.pingme.core.service.MessageActions

/** A snackbar for the chat: fixed text, a counted text, or the network's own reason, maybe with Undo. */
class ChatNotice(
    @param:StringRes val text: Int? = null,
    @param:androidx.annotation.PluralsRes val plural: Int? = null,
    val count: Int = 0,
    val reason: String? = null,
    val arg: String? = null,
    val undo: (() -> Unit)? = null,
    val onGone: () -> Unit = {},
)

/**
 * What press and hold, double tap, and multi-select do to messages (UI_DESIGN.md 3.3, 5.3,
 * 5.4, 10.5). Kept apart from the view model so each stays small.
 */
class MessageMenu(
    private val scope: CoroutineScope,
    private val actions: MessageActions,
) {
    private val chosen = MutableStateFlow<Set<MessageId>>(emptySet())
    private val pendingDeletes = MutableStateFlow<Set<MessageId>>(emptySet())
    private val editingNow = MutableStateFlow<Message?>(null)
    private val channel = Channel<ChatNotice>(Channel.BUFFERED)
    private var toldAboutFallback = false

    /** Messages picked in multi-select. */
    val selection: StateFlow<Set<MessageId>> = chosen

    /** Deleted for me but still inside their Undo window: hidden, not yet gone. */
    val hidden: StateFlow<Set<MessageId>> = pendingDeletes

    /** The message whose text the composer is editing. */
    val editing: StateFlow<Message?> = editingNow

    val notices = channel.receiveAsFlow()

    /**
     * Reacts with [emoji], or takes it back when it is already yours. Returns true when this
     * added a reaction (the burst plays), false when it removed one (a small pop plays).
     */
    fun react(
        message: Message,
        emoji: String,
        me: org.pingme.core.model.PersonId?,
    ): Boolean {
        val removing = message.reactions.any { it.senderId == me && it.emoji == emoji }
        attempt { actions.react(message, emoji, remove = removing) }
        return !removing
    }

    /**
     * Double tap sends the double-tap reaction; where the network does not allow it, the first
     * allowed quick reaction instead, saying so once (UI_DESIGN.md 10.5).
     */
    fun doubleTapEmoji(
        wanted: String,
        quick: List<String>,
        rule: ReactionRule?,
    ): String? {
        if (rule !is ReactionRule.Set || wanted in rule.allowed) return wanted
        val fallback = quick.firstOrNull { it in rule.allowed } ?: rule.allowed.firstOrNull() ?: return null
        if (!toldAboutFallback) {
            toldAboutFallback = true
            channel.trySend(ChatNotice(R.string.chat_double_tap_fallback, arg = fallback))
        }
        return fallback
    }

    fun toggle(message: Message) = chosen.update { if (message.id in it) it - message.id else it + message.id }

    fun clearSelection() = chosen.update { emptySet() }

    /** Delete for me, with five seconds to undo (UI_DESIGN.md 5.3). */
    fun deleteForMe(targets: List<Message>) {
        val ids = targets.map { it.id }.toSet()
        pendingDeletes.update { it + ids }
        clearSelection()
        channel.trySend(
            ChatNotice(
                plural = R.plurals.chat_deleted,
                count = targets.size,
                undo = { pendingDeletes.update { it - ids } },
                onGone = {
                    scope.launch {
                        val still = targets.filter { it.id in pendingDeletes.value }
                        actions.deleteForMe(still)
                        pendingDeletes.update { it - ids }
                    }
                },
            ),
        )
    }

    fun deleteForEveryone(targets: List<Message>) {
        clearSelection()
        targets.forEach { message -> attempt { actions.deleteForEveryone(message) } }
    }

    fun startEdit(message: Message?) {
        editingNow.value = message
    }

    fun finishEdit(text: String) {
        val message = editingNow.value ?: return
        editingNow.value = null
        if (text.isBlank() || text == message.body) return
        attempt { actions.edit(message, text.trim()) }
    }

    fun togglePin(
        message: Message,
        pinned: Boolean,
    ) = attempt { if (pinned) actions.unpin(message.id) else actions.pin(message) }

    fun forward(
        targets: List<Message>,
        to: List<ChatId>,
    ) {
        clearSelection()
        attempt { to.forEach { chat -> targets.sortedBy { it.sentAt }.forEach { actions.forward(it, chat) } } }
    }

    /** Runs a network action; a refusal shows the network's reason. */
    private fun attempt(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // The network said no or could not be reached; the message stays as it was.
                channel.send(ChatNotice(R.string.chat_action_failed, reason = e.message))
            }
        }
    }
}
