// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.fbpage

import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.attachment
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.connector.remoteId
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
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
import org.pingme.core.model.Transport
import kotlin.time.Instant

/**
 * Turns the Page API's conversations and messages into PingMe's model for one Page
 * account, and remembers what it has seen: which messages (so a poll reports only new
 * ones) and when each person last wrote (Meta's 24-hour reply window, UI_DESIGN.md 6.6).
 *
 * Ids: a chat is Meta's conversation id ("t_..."); a message is "<conversation>/<message
 * id>"; a person is their page-scoped id.
 */
@Suppress("TooManyFunctions") // One function per shape, plus the lookups the session needs.
class FbPageTranslate(
    private val accountId: AccountId,
    val pageId: String,
    private val pageName: String,
) {
    private val seen = LinkedHashMap<String, Long>()
    private val updated = HashMap<String, Long>()
    private val lastFromPerson = HashMap<String, Long>()
    private val people = HashMap<String, PageUser>()

    fun chatId(conversation: String): ChatId = accountId.chat(conversation)

    fun messageId(
        conversation: String,
        id: String,
    ): MessageId = accountId.message("$conversation/$id")

    fun personId(user: String): PersonId = accountId.person(user)

    @Synchronized
    fun knows(id: MessageId) = id.remoteId.substringAfter('/') in seen

    @Synchronized
    fun timeOf(id: MessageId): Long? = seen[id.remoteId.substringAfter('/')]

    /** When the person in a conversation last wrote; null when nothing from them was seen. */
    @Synchronized
    fun lastFromPerson(conversation: String): Long? = lastFromPerson[conversation]

    /** The other side of a conversation, once it has been listed. */
    @Synchronized
    fun personIn(conversation: String): PageUser? = people[conversation]

    /** Whether a listing shows the conversation changed since it was last seen. */
    @Synchronized
    fun changed(conversation: PageConversation): Boolean = updated[conversation.id] != conversation.updatedAt

    @Synchronized
    fun chat(conversation: PageConversation): ChatSnapshot {
        updated[conversation.id] = conversation.updatedAt
        val other = conversation.participants.firstOrNull { it.id != pageId }
        if (other != null) people[conversation.id] = other
        conversation.messages.forEach { note(conversation.id, it) }
        val newest = conversation.messages.maxByOrNull { it.createdAt }
        return ChatSnapshot(
            id = chatId(conversation.id),
            accountId = accountId,
            kind = ChatKind.DIRECT,
            title = other?.name?.ifBlank { null } ?: "Messenger user",
            participants = listOf(me()) + listOfNotNull(other?.let(::person)),
            unreadCount = if (newest != null && newest.from.id != pageId) 1 else 0,
            lastActivityAt = Instant.fromEpochMilliseconds(conversation.updatedAt),
            folder = null,
            spaceId = null,
            networkRemoteId = conversation.id,
        )
    }

    /** Messages of a conversation not reported before, oldest first. */
    @Synchronized
    fun fresh(conversation: PageConversation): List<MessageSnapshot> =
        conversation.messages
            .filter { it.id !in seen }
            .sortedBy { it.createdAt }
            .map { message(conversation.id, it) }

    @Synchronized
    fun message(
        conversation: String,
        msg: PageMessage,
    ): MessageSnapshot {
        note(conversation, msg)
        val fromMe = msg.from.id == pageId
        val sender = if (fromMe) me() else person(msg.from)
        val at = Instant.fromEpochMilliseconds(msg.createdAt)
        val attachments = attachmentsOf(conversation, msg)
        val message =
            Message(
                id = messageId(conversation, msg.id),
                chatId = chatId(conversation),
                senderId = sender.id,
                sentAt = at,
                receivedAt = at,
                body = bodyOf(msg),
                kind = kindOf(attachments),
                attachments = attachments,
                replyTo = null,
                quote = null,
                editedAt = null,
                deletedForEveryone = false,
                status = if (fromMe) MessageStatus.Sent else MessageStatus.Delivered,
                reactions = emptyList(),
                transport = Transport.NETWORK,
                networkRemoteId = msg.id,
                linkPreview = previewOf(msg),
                isOutgoing = fromMe,
            )
        return MessageSnapshot(message, sender)
    }

    /** A message the Page just sent, as the app should show it. */
    fun sent(
        conversation: String,
        id: String,
        text: String,
        at: Long,
    ): MessageSnapshot =
        message(
            conversation,
            PageMessage(id, at, PageUser(pageId, pageName), text, emptyList(), emptyList(), "", false),
        )

    private fun note(
        conversation: String,
        msg: PageMessage,
    ) {
        if (msg.id !in seen) {
            seen[msg.id] = msg.createdAt
            while (seen.size > REMEMBERED) seen.remove(seen.keys.first())
        }
        if (msg.from.id != pageId && msg.from.id.isNotEmpty()) {
            lastFromPerson[conversation] = maxOf(lastFromPerson[conversation] ?: 0, msg.createdAt)
            if (people[conversation] == null) people[conversation] = msg.from
        }
    }

    private fun me(): Person = Person(personId(pageId), accountId, "You", null, pageId, null, null)

    private fun person(user: PageUser): Person =
        Person(
            id = personId(user.id),
            accountId = accountId,
            displayName = user.name.ifBlank { "Messenger user" },
            phoneNumber = null,
            networkHandle = user.id,
            avatarPath = null,
            contactId = null,
        )

    private fun bodyOf(msg: PageMessage): String? =
        when {
            msg.text.isNotEmpty() -> {
                msg.text
            }

            msg.unsupported -> {
                UNSUPPORTED
            }

            msg.shares.isNotEmpty() -> {
                msg.shares
                    .first()
                    .let { it.link.ifEmpty { it.name } }
                    .ifEmpty { null }
            }

            else -> {
                null
            }
        }

    private fun previewOf(msg: PageMessage): LinkPreview? {
        val share = msg.shares.firstOrNull() ?: return null
        if (share.link.isEmpty() && share.name.isEmpty()) return null
        val url = share.link.ifEmpty { "https://www.facebook.com/" }
        return LinkPreview(
            url,
            url,
            share.name.ifEmpty { null },
            share.description.ifEmpty { null },
            null,
            Instant.fromEpochMilliseconds(msg.createdAt),
            LinkPreviewSource.NETWORK,
        )
    }

    private fun attachmentsOf(
        conversation: String,
        msg: PageMessage,
    ): List<Attachment> {
        val files =
            msg.attachments.filter { it.url.isNotEmpty() }.mapIndexed { i, a ->
                val kind =
                    when (a.kind) {
                        "video" -> AttachmentKind.VIDEO
                        "gif" -> AttachmentKind.GIF
                        "file" -> if (a.mime.startsWith("audio/")) AttachmentKind.AUDIO else AttachmentKind.FILE
                        else -> AttachmentKind.IMAGE
                    }
                Attachment(
                    id = accountId.attachment("$conversation/${msg.id}/$i"),
                    kind = kind,
                    mimeType = a.mime,
                    fileName = a.name.ifEmpty { null },
                    sizeBytes = 0,
                    localPath = null,
                    remoteRef = a.url,
                    durationMs = a.lengthMs.takeIf { it > 0 },
                    width = a.width.takeIf { it > 0 },
                    height = a.height.takeIf { it > 0 },
                    isEphemeral = false,
                    savedAt = null,
                )
            }
        if (msg.sticker.isEmpty()) return files
        val sticker =
            Attachment(
                id = accountId.attachment("$conversation/${msg.id}/sticker"),
                kind = AttachmentKind.STICKER,
                mimeType = "image/png",
                fileName = null,
                sizeBytes = 0,
                localPath = null,
                remoteRef = msg.sticker,
                durationMs = null,
                width = null,
                height = null,
                isEphemeral = false,
                savedAt = null,
            )
        return files + sticker
    }

    private fun kindOf(attachments: List<Attachment>): MessageKind =
        when (attachments.firstOrNull()?.kind) {
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.AUDIO -> MessageKind.VOICE
            AttachmentKind.STICKER -> MessageKind.STICKER
            AttachmentKind.FILE -> MessageKind.FILE
            else -> MessageKind.TEXT
        }

    companion object {
        private const val REMEMBERED = 4000
        const val UNSUPPORTED = "This kind of message is not supported yet"
    }
}
