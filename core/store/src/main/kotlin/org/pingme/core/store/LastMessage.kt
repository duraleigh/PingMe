// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.Transport
import kotlin.time.Instant

/** What an inbox row previews: the newest message of a chat and who sent it. */
data class LastMessage(
    val body: String?,
    val kind: MessageKind,
    val isOutgoing: Boolean,
    val transport: Transport,
    val sentAt: Instant,
    /** The sender's display name; null when the sender is not in the store. */
    val senderName: String?,
    /** Sending, sent, delivered, read, or failed: shown on the row when the message is yours. */
    val status: MessageStatus = MessageStatus.Sent,
)
