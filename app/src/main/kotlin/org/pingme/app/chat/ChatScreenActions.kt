// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import org.pingme.core.model.Attachment
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.NetworkId
import org.pingme.core.model.PersonId

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
)

/** What a message row needs besides the message. */
class RowContext(
    val network: NetworkId,
    val kind: ChatKind,
    val names: Map<PersonId, String>,
    val onRetry: (Message) -> Unit,
    val onNeed: (Attachment) -> Unit,
    val onQuote: (Message) -> Unit,
)
