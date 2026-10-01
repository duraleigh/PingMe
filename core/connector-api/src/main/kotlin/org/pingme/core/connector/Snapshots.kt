// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

import kotlinx.serialization.Serializable
import org.pingme.core.model.AccountId
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.Person
import org.pingme.core.model.Quote
import org.pingme.core.model.SpaceId
import kotlin.time.Instant

/**
 * A chat as the network sees it. Local-only choices (pinned in PingMe, muted, low priority,
 * obscured, name override, merges) are not part of it; the store keeps those when it
 * applies a snapshot.
 */
data class ChatSnapshot(
    val id: ChatId,
    val accountId: AccountId,
    val kind: ChatKind,
    val title: String,
    val participants: List<Person>,
    val unreadCount: Int,
    val lastActivityAt: Instant,
    val folder: ChatFolder?,
    /** The network's own grouping, such as a WhatsApp community (UI_DESIGN.md 10.4). */
    val spaceId: SpaceId?,
    val networkRemoteId: String,
)

/** A message as the network sees it, with its sender when the network says who that is. */
data class MessageSnapshot(
    val message: Message,
    val sender: Person?,
)

/** What the user is sending. Serializable so a scheduled send survives a restart. */
@Serializable
data class OutgoingMessage(
    /** Chosen by PingMe before sending, so the pending bubble and the result match up. */
    val clientId: MessageId,
    val body: String?,
    val attachments: List<OutgoingAttachment>,
    val replyTo: MessageId?,
    /** Shown as the quoted line where the network has no native reply (UI_DESIGN.md 5.2). */
    val quote: Quote?,
    /**
     * Send over SMS rather than the network's own transport. No longer offered in the UI
     * (owner, 2026-10-01: Google Messages gives paired devices no such choice); kept for
     * the native SMS connector and for retries, which keep a message's transport.
     */
    val forceSms: Boolean,
)

@Serializable
data class OutgoingAttachment(
    val localPath: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val fileName: String?,
    val caption: String?,
    /** Length of a voice note or video, when PingMe knows it. */
    val durationMs: Long? = null,
)

sealed interface SendResult {
    /** The network accepted the message; [message] is how it now looks. */
    data class Sent(
        val message: MessageSnapshot,
    ) : SendResult

    /** Not sent. [retryable] failures show a retry button (UI_DESIGN.md 3.2). */
    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : SendResult
}
