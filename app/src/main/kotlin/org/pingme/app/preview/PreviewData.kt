// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.preview

import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.PersonId
import org.pingme.core.model.Reaction
import org.pingme.core.model.Transport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Sample data for screen previews (BUILD_PLAN.md P2.8: every screen has a preview). */
internal object PreviewData {
    val now: Instant = Instant.parse("2026-09-30T18:00:00Z")

    val account =
        Account(
            AccountId("GMESSAGES"),
            NetworkId.GMESSAGES,
            "RCS",
            0,
            ConnectionState.Connected,
            true,
            NotificationMode.NORMAL,
            "ref",
        )

    val sam = PersonId("GMESSAGES/sam")
    val me = PersonId("GMESSAGES/me")

    val chat =
        Chat(
            ChatId("GMESSAGES/sam"),
            account.id,
            ChatKind.DIRECT,
            "Sam Ortiz",
            listOf(sam),
            0,
            now,
            false,
            null,
            false,
            null,
            false,
            false,
            false,
            null,
            null,
            null,
            AvatarSource.Initials,
            null,
            null,
            "sam",
        )

    fun message(
        id: String,
        body: String,
        ago: Duration,
        outgoing: Boolean = false,
        reactions: List<Reaction> = emptyList(),
    ) = Message(
        id = MessageId("GMESSAGES/$id"),
        chatId = chat.id,
        senderId = if (outgoing) me else sam,
        sentAt = now - ago,
        receivedAt = now - ago,
        body = body,
        kind = MessageKind.TEXT,
        attachments = emptyList(),
        replyTo = null,
        quote = null,
        editedAt = null,
        deletedForEveryone = false,
        status = if (outgoing) MessageStatus.Read else MessageStatus.Delivered,
        reactions = reactions,
        transport = Transport.NETWORK,
        networkRemoteId = id,
        linkPreview = null,
        isOutgoing = outgoing,
    )

    /** A short conversation, newest first. */
    val messages =
        listOf(
            message("5", "Are you still coming tonight?", 1.minutes),
            message("4", "Perfect, see you there", 12.minutes, outgoing = true),
            message("3", "Bring the board game!", 13.minutes, reactions = listOf(Reaction("👍", me, now))),
            message("2", "Dinner at 7?", 30.minutes),
            message("1", "Hey! Long time 😄", 31.minutes),
        )
}
