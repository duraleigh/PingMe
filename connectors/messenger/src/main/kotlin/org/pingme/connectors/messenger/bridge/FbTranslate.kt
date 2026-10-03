// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger.bridge

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
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.LinkPreview
import org.pingme.core.model.LinkPreviewSource
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.model.Quote
import org.pingme.core.model.Reaction
import org.pingme.core.model.Transport
import kotlin.time.Instant

/**
 * Turns what the Go bridge reports into PingMe's model for one account (BUILD_PLAN.md
 * Phase 6, network 6): threads into chats with their folder (the inbox, or Requests for
 * what Messenger holds back; UI_DESIGN.md 6.4), messages into messages, and events into
 * [ConnectorEvent]s. It remembers every thread and message it has seen, so a repeat is an
 * update and a read marker can name its message's time.
 *
 * Ids: a chat is its thread key; a message is "<thread>/<message id>"; a person is their
 * user id.
 */
@Suppress("TooManyFunctions") // One function per shape that crosses the bridge, plus the lookups the session needs.
class FbTranslate(
    private val accountId: AccountId,
) {
    private val threads = HashMap<String, FbThread>()
    private val seen = LinkedHashMap<String, Seen>()
    private val names = HashMap<String, FbUser>()

    class Seen(
        val thread: String,
        val sender: String,
        val fromMe: Boolean,
        val timestamp: Long,
        val status: MessageStatus,
    )

    @Volatile var ownId: String = ""

    fun parse(json: String): FbEvent = fbJson.decodeFromString(FbEvent.serializer(), json)

    fun threadJson(json: String): FbThread = fbJson.decodeFromString(FbThread.serializer(), json)

    fun threadsJson(json: String): List<FbThread> =
        fbJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(FbThread.serializer()), json)

    fun messageJson(json: String): FbMessage = fbJson.decodeFromString(FbMessage.serializer(), json)

    fun messagesJson(json: String): List<FbMessage> =
        fbJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(FbMessage.serializer()), json)

    fun usersJson(json: String): List<FbUser> =
        fbJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(FbUser.serializer()), json)

    fun chatId(thread: String): ChatId = accountId.chat(thread)

    fun messageId(
        thread: String,
        id: String,
    ): MessageId = accountId.message("$thread/$id")

    fun personId(user: String): PersonId = accountId.person(user)

    @Synchronized
    fun seen(id: MessageId): Seen? = seen[id.value.substringAfter('/')]

    @Synchronized
    fun knows(thread: String) = thread in threads

    /** Data events become connector events; control events return nothing. */
    @Synchronized
    fun translate(event: FbEvent): List<ConnectorEvent> =
        when (event) {
            is FbEvent.Thread -> threadEvents(event.thread)
            is FbEvent.Message -> messageEvents(event.message)
            is FbEvent.Reaction -> reactionEvents(event)
            is FbEvent.Edit -> editEvents(event)
            is FbEvent.Unsent -> unsentEvents(event)
            is FbEvent.ThreadGone -> listOf(ConnectorEvent.ChatRemoved(accountId, chatId(event.thread)))
            is FbEvent.ReadByMe -> readByMeEvents(event)
            is FbEvent.ReadReceipt -> readReceiptEvents(event)
            is FbEvent.Typing -> typingEvents(event)
            else -> emptyList()
        }

    /** A thread as a chat, remembered for later lookups. */
    @Synchronized
    fun chat(thread: FbThread): ChatSnapshot {
        threads[thread.id] = thread
        thread.users.forEach { names[it.id] = it }
        if (ownId.isEmpty()) thread.users.firstOrNull { it.isMe }?.let { ownId = it.id }
        return snapshot(thread)
    }

    @Synchronized
    fun message(msg: FbMessage): MessageSnapshot = snapshotOf(msg)

    private fun snapshot(thread: FbThread): ChatSnapshot {
        val others = thread.users.filter { !it.isMe && it.id != ownId }
        val newest = thread.messages.filter { it.kind != "system" }.maxByOrNull { it.timestamp }
        val unreadFromOthers = newest != null && newest.sender != ownId && thread.readAt < newest.timestamp
        return ChatSnapshot(
            id = chatId(thread.id),
            accountId = accountId,
            kind = if (thread.isGroup) ChatKind.GROUP else ChatKind.DIRECT,
            title = thread.title.ifBlank { others.joinToString { displayName(it) } }.ifBlank { "Messenger" },
            participants = listOf(me()) + others.map(::person),
            unreadCount = if (unreadFromOthers) 1 else 0,
            lastActivityAt = Instant.fromEpochMilliseconds(thread.lastMessageAt),
            folder = folderOf(thread.folder),
            spaceId = null,
            networkRemoteId = thread.id,
        )
    }

    private fun me(): Person = Person(personId(ownId.ifEmpty { "me" }), accountId, "You", null, ownId, null, null)

    private fun person(user: FbUser): Person =
        Person(
            id = personId(user.id),
            accountId = accountId,
            displayName = displayName(user),
            phoneNumber = null,
            networkHandle = user.id,
            avatarPath = null,
            contactId = null,
        )

    private fun displayName(user: FbUser): String =
        when {
            user.isMe || user.id == ownId -> "You"
            user.name.isNotBlank() -> user.name
            else -> "Messenger user"
        }

    private fun personOf(id: String): Person =
        when {
            id == ownId -> me()
            else -> names[id]?.let(::person) ?: Person(personId(id), accountId, "Messenger user", null, id, null, null)
        }

    private fun threadEvents(thread: FbThread): List<ConnectorEvent> {
        val chat = chat(thread)
        val plain = thread.messages.filter { it.kind != "system" }
        val batch =
            ConnectorEvent.HistoryBatch(accountId, chat.id, plain.map { snapshotOf(it) }, complete = !thread.moreBefore)
        return listOf(ConnectorEvent.ChatUpdated(accountId, chat), batch)
    }

    private fun messageEvents(msg: FbMessage): List<ConnectorEvent> {
        if (msg.kind == "system") return emptyList()
        val before = seen["${msg.thread}/${msg.id}"]
        val snapshot = snapshotOf(msg)
        return listOf(
            if (before != null) {
                ConnectorEvent.MessageUpdated(accountId, snapshot)
            } else {
                ConnectorEvent.NewMessage(accountId, snapshot)
            },
        )
    }

    private fun snapshotOf(msg: FbMessage): MessageSnapshot {
        val fromMe = msg.sender == ownId
        val sender = personOf(msg.sender)
        val sentAt = Instant.fromEpochMilliseconds(msg.timestamp)
        val attachments = attachmentsOf(msg)
        val status =
            bestOf(seen["${msg.thread}/${msg.id}"]?.status, if (fromMe) MessageStatus.Sent else MessageStatus.Delivered)
        remember(msg, fromMe, status)
        val message =
            Message(
                id = messageId(msg.thread, msg.id),
                chatId = chatId(msg.thread),
                senderId = sender.id,
                sentAt = sentAt,
                receivedAt = sentAt,
                body = bodyOf(msg),
                kind = kindOf(msg, attachments),
                attachments = attachments,
                replyTo = msg.replyTo.takeIf { it.isNotEmpty() }?.let { messageId(msg.thread, it) },
                quote = msg.replyTo.takeIf { it.isNotEmpty() }?.let { Quote("", msg.replyText) },
                editedAt = if (msg.edited) sentAt else null,
                deletedForEveryone = msg.unsent,
                status = status,
                reactions =
                    msg.reactions.map {
                        Reaction(it.emoji, personId(it.sender), Instant.fromEpochMilliseconds(it.timestamp))
                    },
                transport = Transport.NETWORK,
                networkRemoteId = msg.id,
                linkPreview = previewOf(msg),
                isOutgoing = fromMe,
            )
        return MessageSnapshot(message, sender)
    }

    private fun bodyOf(msg: FbMessage): String? =
        when {
            msg.unsent -> null
            msg.kind == "unsupported" -> msg.text.ifEmpty { UNSUPPORTED }
            msg.kind == "share" -> msg.text.ifEmpty { null } ?: msg.share?.url?.ifEmpty { null } ?: msg.share?.title
            else -> msg.text.ifEmpty { null }
        }

    // A shared link or post shows as a card with its title and picture (UI_DESIGN.md 10.12).
    private fun previewOf(msg: FbMessage): LinkPreview? {
        val share = msg.share ?: return null
        if (share.url.isEmpty() && share.title.isEmpty()) return null
        val url = share.url.ifEmpty { "https://www.facebook.com/" }
        return LinkPreview(
            url,
            url,
            share.title.ifEmpty { null },
            share.subtitle.ifEmpty { null },
            null,
            Instant.fromEpochMilliseconds(msg.timestamp),
            LinkPreviewSource.NETWORK,
        )
    }

    private fun attachmentsOf(msg: FbMessage): List<Attachment> =
        msg.media.filter { it.url.isNotEmpty() }.mapIndexed { i, media ->
            val kind = attachmentKind(media.kind)
            Attachment(
                id = accountId.attachment("${msg.thread}/${msg.id}/$i"),
                kind = kind,
                mimeType = media.mime.ifEmpty { defaultMime(kind) },
                fileName = media.fileName.ifEmpty { null },
                sizeBytes = media.size,
                localPath = null,
                remoteRef = fbJson.encodeToString(FbMedia.serializer(), media),
                durationMs = media.durationMs.takeIf { it > 0 }?.toLong(),
                width = media.width.takeIf { it > 0 },
                height = media.height.takeIf { it > 0 },
                isEphemeral = false,
                savedAt = null,
            )
        }

    private fun kindOf(
        msg: FbMessage,
        attachments: List<Attachment>,
    ): MessageKind =
        when (attachments.firstOrNull()?.kind) {
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.VOICE -> MessageKind.VOICE
            AttachmentKind.STICKER -> MessageKind.STICKER
            AttachmentKind.FILE -> MessageKind.FILE
            else -> if (msg.unsent) MessageKind.DELETED else MessageKind.TEXT
        }

    private fun remember(
        msg: FbMessage,
        fromMe: Boolean,
        status: MessageStatus,
    ) {
        val key = "${msg.thread}/${msg.id}"
        seen.remove(key)
        seen[key] = Seen(msg.thread, msg.sender, fromMe, msg.timestamp, status)
        while (seen.size > REMEMBERED_MESSAGES) seen.remove(seen.keys.first())
    }

    private fun reactionEvents(event: FbEvent.Reaction): List<ConnectorEvent> {
        val reaction =
            Reaction(
                event.reaction.emoji,
                personId(event.reaction.sender),
                Instant.fromEpochMilliseconds(event.reaction.timestamp),
            )
        val thread =
            event.thread.ifEmpty {
                seen.entries
                    .firstOrNull { it.key.endsWith("/${event.message}") }
                    ?.value
                    ?.thread
                    .orEmpty()
            }
        return listOf(
            ConnectorEvent.ReactionChanged(
                accountId,
                messageId(thread, event.message),
                reaction,
                removed = event.removed,
            ),
        )
    }

    private fun editEvents(event: FbEvent.Edit): List<ConnectorEvent> {
        val thread =
            event.thread.takeIf { it.isNotEmpty() && it != "0" }
                ?: seen.entries
                    .firstOrNull { it.key.endsWith("/${event.message}") }
                    ?.value
                    ?.thread
                ?: return emptyList()
        val at = Instant.fromEpochMilliseconds(event.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis())
        return listOf(
            ConnectorEvent.MessageEdited(accountId, chatId(thread), messageId(thread, event.message), event.text, at),
        )
    }

    private fun unsentEvents(event: FbEvent.Unsent): List<ConnectorEvent> {
        seen.remove("${event.thread}/${event.message}")
        return listOf(
            ConnectorEvent.MessageRevoked(accountId, chatId(event.thread), messageId(event.thread, event.message)),
        )
    }

    private fun readByMeEvents(event: FbEvent.ReadByMe): List<ConnectorEvent> {
        val known = threads[event.thread] ?: return emptyList()
        val updated = known.copy(readAt = maxOf(known.readAt, event.timestamp))
        threads[event.thread] = updated
        return listOf(ConnectorEvent.ChatUpdated(accountId, snapshot(updated)))
    }

    // The other side read up to a time: every message of yours before it is read.
    private fun readReceiptEvents(event: FbEvent.ReadReceipt): List<ConnectorEvent> {
        if (event.sender == ownId) return readByMeEvents(FbEvent.ReadByMe(event.thread, event.timestamp))
        return seen.entries
            .filter { it.value.thread == event.thread && it.value.fromMe && it.value.timestamp <= event.timestamp }
            .filter { it.value.status != MessageStatus.Read }
            .map { (key, before) ->
                seen[key] = Seen(before.thread, before.sender, before.fromMe, before.timestamp, MessageStatus.Read)
                ConnectorEvent.StatusChanged(accountId, accountId.message(key), MessageStatus.Read)
            }
    }

    private fun typingEvents(event: FbEvent.Typing): List<ConnectorEvent> =
        listOf(ConnectorEvent.Typing(accountId, chatId(event.thread), personId(event.sender), event.typing))

    companion object {
        private const val REMEMBERED_MESSAGES = 4000
        const val UNSUPPORTED = "This kind of message is not supported yet"

        /** What Messenger holds back from the inbox is a request (UI_DESIGN.md 6.4). */
        private val REQUEST_FOLDERS = setOf("pending", "other", "spam", "hidden", "hidden_requests")

        fun folderOf(folder: String): ChatFolder =
            if (folder.lowercase() in REQUEST_FOLDERS) ChatFolder.REQUESTS else ChatFolder.PRIMARY

        fun attachmentKind(kind: String): AttachmentKind =
            when (kind) {
                "video" -> AttachmentKind.VIDEO
                "gif" -> AttachmentKind.GIF
                "voice" -> AttachmentKind.VOICE
                "sticker" -> AttachmentKind.STICKER
                "file" -> AttachmentKind.FILE
                else -> AttachmentKind.IMAGE
            }

        private fun defaultMime(kind: AttachmentKind) =
            when (kind) {
                AttachmentKind.VIDEO -> "video/mp4"
                AttachmentKind.VOICE -> "audio/mp4"
                AttachmentKind.FILE -> "application/octet-stream"
                else -> "image/jpeg"
            }

        private val TICK_ORDER =
            listOf(MessageStatus.Sending, MessageStatus.Sent, MessageStatus.Delivered, MessageStatus.Read)

        private fun rank(status: MessageStatus) = TICK_ORDER.indexOf(status).coerceAtLeast(0)

        fun bestOf(
            before: MessageStatus?,
            now: MessageStatus,
        ): MessageStatus =
            if (before == null || now is MessageStatus.Failed || rank(now) >= rank(before)) now else before
    }
}
