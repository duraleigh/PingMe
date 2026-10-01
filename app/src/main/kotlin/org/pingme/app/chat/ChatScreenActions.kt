// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.pingme.core.model.Attachment
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.NetworkId
import org.pingme.core.model.PersonId
import org.pingme.core.service.IncomingReaction

// What the chat screen's parts can ask for, gathered so each part takes one argument.

/** What the header's buttons do. Null ones are not built yet and show greyed out. */
class HeaderActions(
    val onBack: (() -> Unit)?,
    val onCall: (video: Boolean) -> Unit,
    val onDetails: (() -> Unit)? = null,
    val onSearch: (() -> Unit)? = null,
)

/** Everything the chat screen can ask its view model for. */
class ChatScreenActions(
    val header: HeaderActions,
    val onSend: (String) -> Unit,
    val onReply: (Message?) -> Unit,
    val onRetry: (Message) -> Unit,
    val onUnpin: (Message) -> Unit,
    val onLoadOlder: () -> Unit,
    val onNeed: (Attachment) -> Unit,
    val onTyping: (String) -> Unit,
    /** Press and hold, double tap, delete, select; null in previews. */
    val menu: MessageMenu? = null,
    val onRememberEmoji: (String) -> Unit = {},
    val incoming: Flow<IncomingReaction> = emptyFlow(),
    val forwardTargets: List<Chat> = emptyList(),
    val composer: ComposerHooks = ComposerHooks(),
    val uploads: Map<org.pingme.core.model.MessageId, Float> = emptyMap(),
)

/** The composer's extras: attachments and other ways to send. Null ones are not offered. */
class ComposerHooks(
    val outbox: org.pingme.app.chat.attach.Outbox? = null,
    /** "Send as SMS" from the split button, for chats paired through Google Messages. */
    val onSendSms: ((String) -> Unit)? = null,
    val onProblem: (org.pingme.app.chat.attach.AttachProblem) -> Unit = {},
)

/** What a message row needs besides the message. */
class RowContext(
    val network: NetworkId,
    val kind: ChatKind,
    val names: Map<PersonId, String>,
    val onRetry: (Message) -> Unit,
    val onNeed: (Attachment) -> Unit,
    val onQuote: (Message) -> Unit,
    /** How far each sending message's media has got. */
    val uploads: Map<org.pingme.core.model.MessageId, Float> = emptyMap(),
)

/** One line of the action card. [enabled] false shows it greyed with [reason] (UI_DESIGN.md 1). */
data class MessageAction(
    @param:androidx.annotation.StringRes val label: Int,
    @param:androidx.annotation.DrawableRes val icon: Int,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)
