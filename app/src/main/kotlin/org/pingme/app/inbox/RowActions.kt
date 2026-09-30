// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.service.ChatActions
import org.pingme.core.ui.theme.SwipeAction

/** Something a swipe or the action sheet does to a chat (UI_DESIGN.md 3.1). */
enum class ChatAction { PIN, READ, MUTE, ARCHIVE, LOW_PRIORITY, OBSCURE, DELETE }

/** A snackbar to show, with what Undo does (if anything) and what happens when it goes away. */
class InboxMessage(
    @param:StringRes val text: Int,
    val chatTitle: String?,
    val undo: (() -> Unit)? = null,
    val onGone: () -> Unit = {},
)

/**
 * What swipes and the action sheet do, shared by the inbox and the other chat lists. Every
 * action toggles (pin or unpin, archive or unarchive, ...). Leaving the list offers Undo;
 * a delete waits until the Undo snackbar has gone.
 */
class RowActions(
    private val scope: CoroutineScope,
    private val actions: ChatActions,
) {
    private val pending = MutableStateFlow<Set<ChatId>>(emptySet())

    /** Chats deleted from the list but still waiting out their Undo. */
    val pendingDeletes: StateFlow<Set<ChatId>> = pending

    private val channel = Channel<InboxMessage>(Channel.BUFFERED)
    val messages = channel.receiveAsFlow()

    fun swipe(
        row: ChatRow,
        action: SwipeAction,
    ) {
        when (action) {
            SwipeAction.PIN -> perform(row, ChatAction.PIN)
            SwipeAction.ARCHIVE -> perform(row, ChatAction.ARCHIVE)
            SwipeAction.MUTE -> perform(row, ChatAction.MUTE)
            SwipeAction.MARK_READ -> perform(row, ChatAction.READ)
            SwipeAction.LOW_PRIORITY -> perform(row, ChatAction.LOW_PRIORITY)
            SwipeAction.DELETE -> perform(row, ChatAction.DELETE)
            SwipeAction.OFF -> Unit
        }
    }

    fun perform(
        row: ChatRow,
        action: ChatAction,
    ) {
        val chat = row.chat
        scope.launch {
            when (action) {
                ChatAction.PIN -> {
                    pin(chat)
                }

                ChatAction.READ -> {
                    actions.setRead(chat.id, read = chat.unreadCount > 0)
                }

                ChatAction.MUTE -> {
                    actions.setMuted(chat.id, !chat.isMuted)
                }

                ChatAction.ARCHIVE -> {
                    if (chat.isArchived) {
                        actions.setArchived(chat.id, false)
                    } else {
                        undoable(
                            chat,
                            R.string.inbox_archived,
                            { actions.setArchived(chat.id, true) },
                        ) { actions.setArchived(chat.id, false) }
                    }
                }

                ChatAction.LOW_PRIORITY -> {
                    if (chat.isLowPriority) {
                        actions.setLowPriority(chat.id, false)
                    } else {
                        undoable(chat, R.string.inbox_low_priority_done, { actions.setLowPriority(chat.id, true) }) {
                            actions.setLowPriority(chat.id, false)
                        }
                    }
                }

                ChatAction.OBSCURE -> {
                    actions.setObscured(chat.id, !chat.isObscured)
                }

                ChatAction.DELETE -> {
                    delete(chat)
                }
            }
        }
    }

    /** Tells the user something without an Undo, such as why a request failed. */
    fun tell(
        @StringRes text: Int,
        chatTitle: String? = null,
    ) {
        channel.trySend(InboxMessage(text, chatTitle))
    }

    private suspend fun pin(chat: Chat) {
        if (chat.isPinned) {
            actions.setPinned(chat.id, false)
        } else if (!actions.setPinned(chat.id, true)) {
            channel.send(InboxMessage(R.string.inbox_pin_limit, null))
        }
    }

    private suspend fun undoable(
        chat: Chat,
        @StringRes text: Int,
        doIt: suspend () -> Unit,
        undo: suspend () -> Unit,
    ) {
        val wasPinned = chat.isPinned
        doIt()
        channel.send(
            InboxMessage(text, chat.title(), undo = {
                scope.launch {
                    undo()
                    if (wasPinned) actions.setPinned(chat.id, true)
                }
            }),
        )
    }

    private suspend fun delete(chat: Chat) {
        pending.update { it + chat.id }
        channel.send(
            InboxMessage(
                R.string.inbox_deleted,
                chat.title(),
                undo = { pending.update { it - chat.id } },
                onGone = {
                    scope.launch {
                        if (chat.id in pending.value) {
                            actions.delete(chat.id)
                            pending.update { it - chat.id }
                        }
                    }
                },
            ),
        )
    }

    private fun Chat.title() = nameOverride ?: title
}
