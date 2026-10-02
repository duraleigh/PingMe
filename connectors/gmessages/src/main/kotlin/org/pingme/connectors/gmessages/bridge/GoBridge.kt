// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages.bridge

import kotlinx.serialization.Serializable
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.attachment
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.model.Reaction
import org.pingme.core.model.Transport
import kotlin.time.Instant

/**
 * Turns what the Go bridge reports into PingMe's model for one account (BUILD_PLAN.md
 * P3.1): conversations into [ChatSnapshot]s, messages into [MessageSnapshot]s, and
 * events into [ConnectorEvent]s. It remembers the participants of every conversation it
 * has seen, so senders and typing numbers resolve to people, and which messages it has
 * seen, so a repeat is an update and not a new message.
 *
 * The event loop, the history worker, and the chat-list sync all call in from their own
 * threads, so every public method holds the one lock; the maps are not thread-safe.
 */
@Suppress("TooManyFunctions") // One function per shape that crosses the bridge, plus the lookups the session needs.
class GoBridge(
    private val accountId: AccountId,
) {
    private val conversations = HashMap<String, GmConversation>()

    /** What was last seen of each message, newest last; bounded, so memory stays flat. */
    private val seen = LinkedHashMap<String, Seen>()

    private class Seen(
        val conversationId: String,
        val timestamp: Long,
        val reactions: Set<Reaction>,
        val status: MessageStatus,
    )

    /** The account's own participant IDs: one per SIM, plus every "me" entry in a chat. */
    private val selfIds = HashSet<String>()

    fun parse(json: String): GmEvent = gmJson.decodeFromString(GmEvent.serializer(), json)

    fun conversationPage(json: String): GmConversationPage =
        gmJson.decodeFromString(GmConversationPage.serializer(), json)

    fun messagePage(json: String): GmMessagePage = gmJson.decodeFromString(GmMessagePage.serializer(), json)

    fun conversation(json: String): GmConversation = gmJson.decodeFromString(GmConversation.serializer(), json)

    /**
     * Whether a message is something to show. Google Messages' system notes (tombstones:
     * "switched to RCS", "X joined") are not messages and never become bubbles; the phone
     * shows them as centred notes, which PingMe does not have yet.
     */
    fun isShown(msg: GmMessage): Boolean = !msg.hide && msg.direction != "tombstone"

    /** Whether this conversation has been seen (listed, fetched, or announced by the phone). */
    @Synchronized
    fun knows(conversationId: String): Boolean = conversationId in conversations

    /** The participant ID messages go out as in a conversation, or null when unknown. */
    @Synchronized
    fun outgoingId(conversationId: String): String? = conversations[conversationId]?.outgoingId?.ifEmpty { null }

    /** Whether a conversation sends over RCS, or null when the chat is unknown. */
    @Synchronized
    fun isRcs(conversationId: String): Boolean? = conversations[conversationId]?.outgoingIsRcs

    /** Your own reaction on a message, as last seen, or null. */
    @Synchronized
    fun myReaction(messageId: String): String? = seen[messageId]?.reactions?.firstOrNull { it.senderId.isSelf() }?.emoji

    /** The conversation a message was seen in, or null when it has not been seen. */
    @Synchronized
    fun conversationOf(messageId: String): String? = seen[messageId]?.conversationId

    /** A cursor for the messages older than a seen message, or null when it has not been seen. */
    @Synchronized
    fun cursorBefore(messageId: String): GmCursor? =
        seen[messageId]?.let { GmCursor(messageId, it.timestamp / MICROS_PER_MILLI) }

    /** Data events become connector events; control events (ready, auth, logout) return nothing. */
    @Synchronized
    fun translate(event: GmEvent): List<ConnectorEvent> =
        when (event) {
            is GmEvent.Conversation -> {
                listOf(chatEvent(event.conversation))
            }

            is GmEvent.Message -> {
                messageEvents(event.message, event.isOld)
            }

            is GmEvent.Typing -> {
                listOfNotNull(typing(event))
            }

            is GmEvent.Settings -> {
                event.settings.sims.forEach { selfIds += it.participantId }
                emptyList()
            }

            else -> {
                emptyList()
            }
        }

    /** A conversation as a chat; also remembers its participants. */
    @Synchronized
    fun chat(conv: GmConversation): ChatSnapshot {
        conversations[conv.id] = conv
        conv.participants.filter { it.isMe }.forEach { selfIds += it.id }
        val visible = conv.participants.filter { it.isVisible }
        val others = visible.filter { !it.isMe }
        return ChatSnapshot(
            id = accountId.chat(conv.id),
            accountId = accountId,
            kind = if (conv.isGroup) ChatKind.GROUP else ChatKind.DIRECT,
            title = conv.name.takeIf { conv.isGroup && it.isNotBlank() } ?: others.joinToString { displayName(it) },
            participants = visible.map(::person),
            // Google Messages says only whether a chat is unread, not how many are.
            unreadCount = if (conv.unread) 1 else 0,
            lastActivityAt = micros(conv.lastMessageAt),
            folder = null,
            spaceId = null,
            networkRemoteId = conv.id,
        )
    }

    /** Whether the phone has dropped a chat (deleted, binned, spam, or blocked). */
    fun isGone(conv: GmConversation) = conv.status in GONE_STATUSES

    @Synchronized
    fun message(msg: GmMessage): MessageSnapshot {
        val id = accountId.message(msg.id)
        val chatId = accountId.chat(msg.conversationId)
        val sentAt = micros(msg.timestamp)
        val reactions =
            msg.reactions.flatMap { r -> r.participantIds.map { Reaction(r.emoji, accountId.person(it), sentAt) } }
        val sender = sender(msg)
        val deleted = msg.direction == "deleted"
        val body = listOf(msg.subject, msg.text, msg.pendingDownload).filter { it.isNotBlank() }.joinToString("\n")
        val attachments = if (deleted) emptyList() else msg.media.map { attachment(msg, it) }
        // The phone's events can arrive out of order: a tick never goes backwards.
        val status = bestOf(seen[msg.id]?.status, status(msg))
        if (deleted) seen.remove(msg.id) else remember(msg, reactions.toSet(), status)
        val message =
            Message(
                id = id,
                chatId = chatId,
                senderId = sender?.id ?: accountId.person(msg.participantId.ifEmpty { "unknown" }),
                sentAt = sentAt,
                receivedAt = sentAt,
                body = body.ifEmpty { msg.statusText.takeIf { msg.direction == "tombstone" } },
                kind = kind(msg, attachments),
                attachments = attachments,
                replyTo = msg.replyToId.takeIf { it.isNotEmpty() }?.let { accountId.message(it) },
                quote = null,
                editedAt = null,
                deletedForEveryone = deleted,
                status = status,
                reactions = reactions,
                transport = transport(msg.transport),
                networkRemoteId = msg.id,
                linkPreview = null,
                isOutgoing = msg.fromMe,
            )
        return MessageSnapshot(message, sender)
    }

    /** What an attachment's remoteRef holds, so a download knows which file and key to ask for. */
    @Serializable
    data class MediaRef(
        val messageId: String,
        val partId: String,
        val mediaId: String,
        val key: String,
        val thumbnailMediaId: String,
        val thumbnailKey: String,
    ) {
        fun encode(): String = gmJson.encodeToString(serializer(), this)

        companion object {
            fun decode(ref: String): MediaRef = gmJson.decodeFromString(serializer(), ref)
        }
    }

    private fun chatEvent(conv: GmConversation): ConnectorEvent =
        if (isGone(conv)) {
            conversations.remove(conv.id)
            ConnectorEvent.ChatRemoved(accountId, accountId.chat(conv.id))
        } else {
            ConnectorEvent.ChatUpdated(accountId, chat(conv))
        }

    private fun messageEvents(
        msg: GmMessage,
        isOld: Boolean,
    ): List<ConnectorEvent> {
        val id = accountId.message(msg.id)
        if (!isShown(msg)) return emptyList()
        if (msg.direction == "deleted") {
            seen.remove(msg.id)
            return listOf(ConnectorEvent.MessageRemoved(accountId, accountId.chat(msg.conversationId), id))
        }
        val before = seen[msg.id]
        val snapshot = message(msg)
        val now = snapshot.message.reactions.toSet()
        val main =
            if (before != null || isOld) {
                ConnectorEvent.MessageUpdated(accountId, snapshot)
            } else {
                ConnectorEvent.NewMessage(accountId, snapshot)
            }
        // A message seen for the first time carries its reactions already; only later changes flip.
        if (before == null) return listOf(main)
        val changes =
            (now - before.reactions).map { ConnectorEvent.ReactionChanged(accountId, id, it, removed = false) } +
                (before.reactions - now).map { ConnectorEvent.ReactionChanged(accountId, id, it, removed = true) }
        return listOf(main) + changes
    }

    private fun remember(
        msg: GmMessage,
        reactions: Set<Reaction>,
        status: MessageStatus,
    ) {
        seen.remove(msg.id)
        seen[msg.id] = Seen(msg.conversationId, msg.timestamp, reactions, status)
        while (seen.size > REMEMBERED_MESSAGES) seen.remove(seen.keys.first())
    }

    private fun typing(event: GmEvent.Typing): ConnectorEvent.Typing? {
        val conv = conversations[event.conversationId] ?: return null
        val who =
            conv.participants.firstOrNull { it.number == event.number && it.isVisible && !it.isMe }
                ?: conv.participants.firstOrNull { it.number == event.number }
                ?: return null
        return ConnectorEvent.Typing(accountId, accountId.chat(conv.id), accountId.person(who.id), event.typing)
    }

    private fun sender(msg: GmMessage): Person? {
        val fromMessage = msg.sender?.takeIf { it.id.isNotEmpty() }
        val fromChat = conversations[msg.conversationId]?.participants?.firstOrNull { it.id == msg.participantId }
        return (fromMessage ?: fromChat)?.let(::person)
    }

    private fun person(p: GmParticipant) =
        Person(
            id = accountId.person(p.id),
            accountId = accountId,
            displayName = displayName(p),
            phoneNumber = p.number.takeIf { it.startsWith("+") },
            networkHandle = p.number.ifEmpty { p.id },
            avatarPath = null,
            contactId = null,
        )

    private fun displayName(p: GmParticipant) =
        when {
            p.isMe -> "You"
            p.fullName.isNotBlank() -> p.fullName
            p.firstName.isNotBlank() -> p.firstName
            p.formattedNumber.isNotBlank() -> p.formattedNumber
            else -> p.number.ifEmpty { p.id }
        }

    private fun attachment(
        msg: GmMessage,
        media: GmMedia,
    ): Attachment {
        val kind = attachmentKind(media.mime)
        val ref = MediaRef(msg.id, media.partId, media.mediaId, media.key, media.thumbnailMediaId, media.thumbnailKey)
        return Attachment(
            id = accountId.attachment("${msg.id}/${media.partId.ifEmpty { media.mediaId }}"),
            kind = kind,
            mimeType = media.mime,
            fileName = media.name.ifEmpty { null },
            sizeBytes = media.size,
            localPath = null,
            remoteRef = if (media.pending) null else ref.encode(),
            durationMs = null,
            width = media.width.takeIf { it > 0 }?.toInt(),
            height = media.height.takeIf { it > 0 }?.toInt(),
            isEphemeral = false,
            savedAt = null,
        )
    }

    private fun PersonId.isSelf() = selfIds.any { this == accountId.person(it) }

    private companion object {
        const val REMEMBERED_MESSAGES = 4000
        const val MICROS_PER_MILLI = 1000
        val GONE_STATUSES = setOf("DELETED", "TRASH_FOLDER", "SPAM_FOLDER", "BLOCKED_FOLDER")

        fun micros(value: Long) = Instant.fromEpochMilliseconds(value / MICROS_PER_MILLI)

        fun transport(name: String) =
            when (name) {
                "SMS" -> Transport.SMS
                "MMS" -> Transport.MMS
                else -> Transport.RCS
            }

        fun attachmentKind(mime: String) =
            when {
                mime == "image/gif" -> AttachmentKind.GIF
                mime.startsWith("image/") -> AttachmentKind.IMAGE
                mime.startsWith("video/") -> AttachmentKind.VIDEO
                mime.startsWith("audio/") -> AttachmentKind.AUDIO
                mime == "text/vcard" || mime == "text/x-vcard" -> AttachmentKind.CONTACT
                else -> AttachmentKind.FILE
            }

        fun kind(
            msg: GmMessage,
            attachments: List<Attachment>,
        ) = when {
            msg.direction == "deleted" -> MessageKind.DELETED
            attachments.isEmpty() -> MessageKind.TEXT
            else -> messageKind(attachments.first().kind)
        }

        fun messageKind(kind: AttachmentKind) =
            when (kind) {
                AttachmentKind.IMAGE -> MessageKind.IMAGE
                AttachmentKind.GIF -> MessageKind.GIF
                AttachmentKind.VIDEO -> MessageKind.VIDEO
                AttachmentKind.CONTACT -> MessageKind.CONTACT
                AttachmentKind.LOCATION -> MessageKind.LOCATION
                AttachmentKind.STICKER -> MessageKind.STICKER
                AttachmentKind.VOICE -> MessageKind.VOICE
                AttachmentKind.AUDIO, AttachmentKind.FILE -> MessageKind.FILE
            }

        /** The further-along of two statuses; a failure always shows. */
        fun bestOf(
            before: MessageStatus?,
            now: MessageStatus,
        ): MessageStatus =
            if (before == null || now is MessageStatus.Failed || rank(now) >= rank(before)) now else before

        /** The order ticks move in; anything else ranks with Sending. */
        val TICK_ORDER = listOf(MessageStatus.Sending, MessageStatus.Sent, MessageStatus.Delivered, MessageStatus.Read)

        fun rank(status: MessageStatus) = TICK_ORDER.indexOf(status).coerceAtLeast(0)

        fun status(msg: GmMessage): MessageStatus =
            when {
                !msg.fromMe -> MessageStatus.Delivered
                msg.failed -> MessageStatus.Failed(msg.failReason.ifEmpty { "Sending failed" })
                msg.read -> MessageStatus.Read
                msg.delivered -> MessageStatus.Delivered
                msg.sent -> MessageStatus.Sent
                else -> MessageStatus.Sending
            }
    }
}
