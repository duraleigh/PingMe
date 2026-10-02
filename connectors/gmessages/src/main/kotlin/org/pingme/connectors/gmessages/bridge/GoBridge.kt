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

    /**
     * RCS, SMS, and MMS with the same person are one thread (owner, Gate G3): the phone can
     * keep two conversations for one number, and PingMe shows both under the oldest one's
     * id, which never changes while that conversation lives. Keyed by the number's digits.
     */
    private val sameNumber = HashMap<String, MutableSet<String>>()
    private val canonicalOf = HashMap<String, String>()

    /** When each conversation last had a message, so sends go where the talk is now. */
    private val latestAt = HashMap<String, Long>()

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

    /** The conversation a chat is shown under: the oldest with the same person, or itself. */
    @Synchronized
    fun canonical(conversationId: String): String = canonicalOf[conversationId] ?: conversationId

    /** Every conversation shown under [conversationId]'s chat, itself included. */
    @Synchronized
    fun group(conversationId: String): Set<String> = groupOf(conversationId)

    /**
     * Where a message to this chat goes: among the conversations with the same person, the
     * one that last had a message, so a reply lands where the other side is talking.
     */
    @Synchronized
    fun liveConversation(conversationId: String): String =
        groupOf(conversationId).maxWithOrNull(compareBy({ latestAt[it] ?: 0L }, { it.toLongOrNull() ?: 0L }))
            ?: conversationId

    /** Conversations folded into another chat: their own chat rows, if any, are stale. */
    @Synchronized
    fun aliases(): List<String> = canonicalOf.filter { (id, canonical) -> id != canonical }.keys.toList()

    private fun groupOf(conversationId: String): Set<String> =
        numberOf(conversations[conversationId])?.let { sameNumber[it] } ?: setOf(conversationId)

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
                chatEvents(event.conversation)
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
        latestAt[conv.id] = maxOf(latestAt[conv.id] ?: 0L, conv.lastMessageAt)
        conv.participants.filter { it.isMe }.forEach { selfIds += it.id }
        val canonical = fold(conv)
        val group = groupOf(conv.id).mapNotNull { conversations[it] }
        val visible = conv.participants.filter { it.isVisible }
        val others = visible.filter { !it.isMe }
        return ChatSnapshot(
            id = accountId.chat(canonical),
            accountId = accountId,
            kind = if (conv.isGroup) ChatKind.GROUP else ChatKind.DIRECT,
            title = conv.name.takeIf { conv.isGroup && it.isNotBlank() } ?: others.joinToString { displayName(it) },
            participants = visible.map(::person),
            // Google Messages says only whether a chat is unread, not how many are.
            unreadCount = if (group.any { it.unread }) 1 else 0,
            lastActivityAt = micros(group.maxOf { it.lastMessageAt }),
            folder = null,
            spaceId = null,
            networkRemoteId = canonical,
        )
    }

    // Puts a one-to-one conversation with the others for its number; returns the chat it shows under.
    private fun fold(conv: GmConversation): String {
        val number = numberOf(conv) ?: return conv.id
        val group = sameNumber.getOrPut(number) { HashSet() }
        group += conv.id
        val canonical = group.minWithOrNull(compareBy({ it.toLongOrNull() ?: Long.MAX_VALUE }, { it })) ?: conv.id
        group.forEach { canonicalOf[it] = canonical }
        return canonical
    }

    // The digits of the one other person's number in a one-to-one chat, or null.
    private fun numberOf(conv: GmConversation?): String? {
        if (conv == null || conv.isGroup) return null
        val others = conv.participants.filter { it.isVisible && !it.isMe }
        val number = others.singleOrNull()?.number?.filter { it.isDigit() } ?: return null
        if (number.length < MIN_NUMBER_DIGITS) return null
        // With or without a country code, the same line.
        return number.takeLast(LOCAL_NUMBER_DIGITS)
    }

    /** Whether the phone has dropped a chat (deleted, binned, spam, or blocked). */
    fun isGone(conv: GmConversation) = conv.status in GONE_STATUSES

    @Synchronized
    fun message(msg: GmMessage): MessageSnapshot {
        val id = accountId.message(msg.id)
        val chatId = accountId.chat(canonical(msg.conversationId))
        latestAt[msg.conversationId] = maxOf(latestAt[msg.conversationId] ?: 0L, msg.timestamp)
        val sentAt = micros(msg.timestamp)
        val reactions =
            msg.reactions.flatMap { r -> r.participantIds.map { Reaction(r.emoji, accountId.person(it), sentAt) } }
        val sender = sender(msg)
        val deleted = msg.direction == "deleted"
        // "Downloading message..." is the phone's own progress note, not text anyone sent
        // (owner, Gate G3); a download that failed or needs a tap in Google Messages still says so.
        val note = msg.pendingDownload.takeUnless { it.startsWith(DOWNLOADING) }.orEmpty()
        val body = listOf(msg.subject, msg.text, note).filter { it.isNotBlank() }.joinToString("\n")
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

    private fun chatEvents(conv: GmConversation): List<ConnectorEvent> =
        if (isGone(conv)) {
            forget(conv)
        } else {
            listOf(ConnectorEvent.ChatUpdated(accountId, chat(conv)))
        }

    /**
     * A conversation the phone dropped. Alone, its chat goes. Folded with others for the
     * same number, the chat stays under the oldest one left; if the dropped one was the
     * one the chat showed under, the chat moves to the next oldest.
     */
    private fun forget(conv: GmConversation): List<ConnectorEvent> {
        val shownAs = canonical(conv.id)
        val rest = groupOf(conv.id) - conv.id
        conversations.remove(conv.id)
        latestAt.remove(conv.id)
        canonicalOf.remove(conv.id)
        numberOf(conv)?.let { number ->
            sameNumber[number]?.remove(conv.id)
            if (sameNumber[number].isNullOrEmpty()) sameNumber.remove(number)
        }
        val survivor = rest.mapNotNull { conversations[it] }.firstOrNull()
        val removed = ConnectorEvent.ChatRemoved(accountId, accountId.chat(shownAs))
        return when {
            survivor == null -> listOf(removed)

            // The chat's id changes: the old row goes, the survivor announces the new one.
            shownAs == conv.id -> listOf(removed, ConnectorEvent.ChatUpdated(accountId, chat(survivor)))

            else -> listOf(ConnectorEvent.ChatUpdated(accountId, chat(survivor)))
        }
    }

    private fun messageEvents(
        msg: GmMessage,
        isOld: Boolean,
    ): List<ConnectorEvent> {
        val id = accountId.message(msg.id)
        if (!isShown(msg)) return emptyList()
        if (msg.direction == "deleted") {
            seen.remove(msg.id)
            val chatId = accountId.chat(canonical(msg.conversationId))
            return listOf(ConnectorEvent.MessageRemoved(accountId, chatId, id))
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
        val chatId = accountId.chat(canonical(conv.id))
        return ConnectorEvent.Typing(accountId, chatId, accountId.person(who.id), event.typing)
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
        const val MIN_NUMBER_DIGITS = 7
        const val LOCAL_NUMBER_DIGITS = 10
        const val DOWNLOADING = "Downloading message"
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

                // Any audio plays in the bubble like a voice note (owner, Gate G3).
                AttachmentKind.VOICE, AttachmentKind.AUDIO -> MessageKind.VOICE

                AttachmentKind.FILE -> MessageKind.FILE
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
