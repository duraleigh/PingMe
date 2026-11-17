// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal.bridge

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
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
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
 * Turns what the Go bridge reports into PingMe's model for one Signal account
 * (BUILD_PLAN.md Phase 6, network 3): chats, people, messages, and events into
 * [ConnectorEvent]s. It remembers every chat and every message it has seen, so a repeat
 * is an update, and a receipt (which names only a timestamp) can find its message.
 *
 * Ids: a chat is Signal's chat id (a person's account id, or a group identifier); a
 * message is "<chat>/<sender>:<timestamp>"; a person is their account id.
 */
@Suppress("TooManyFunctions") // One function per shape that crosses the bridge, plus the lookups the session needs.
class SigTranslate(
    private val accountId: AccountId,
) {
    private val chats = HashMap<String, SigChat>()
    private val names = HashMap<String, SigMember>()

    /** What was last seen of each message, newest last; bounded, so memory stays flat. */
    private val seen = LinkedHashMap<String, Seen>()

    class Seen(
        val chat: String,
        val sender: String,
        val fromMe: Boolean,
        val timestamp: Long,
        val status: MessageStatus,
    )

    @Volatile var ownId: String = ""

    @Volatile var ownPhone: String = ""

    fun parse(json: String): SigEvent = sigJson.decodeFromString(SigEvent.serializer(), json)

    fun chatJson(json: String): SigChat = sigJson.decodeFromString(SigChat.serializer(), json)

    fun chatsJson(json: String): List<SigChat> =
        sigJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SigChat.serializer()), json)

    fun messageJson(json: String): SigMessage = sigJson.decodeFromString(SigMessage.serializer(), json)

    fun messagesJson(json: String): List<SigMessage> =
        sigJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SigMessage.serializer()), json)

    fun membersJson(json: String): List<SigMember> =
        sigJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SigMember.serializer()), json)

    fun chatId(chat: String): ChatId = accountId.chat(chat)

    fun messageId(
        chat: String,
        id: String,
    ): MessageId = accountId.message("$chat/$id")

    fun personId(user: String): PersonId = accountId.person(user)

    private val ownReactions = HashMap<MessageId, String>()

    /** Your own reaction on a message, which taking it away must name again. */
    @Synchronized
    fun ownReaction(id: MessageId): String? = ownReactions[id]

    @Synchronized
    fun rememberOwnReaction(
        id: MessageId,
        emoji: String,
        removed: Boolean,
    ) {
        if (removed) ownReactions.remove(id) else ownReactions[id] = emoji
        while (ownReactions.size > REMEMBERED_MESSAGES) ownReactions.remove(ownReactions.keys.first())
    }

    /** The message a PingMe id names, if it has been seen. */
    @Synchronized
    fun seen(id: MessageId): Seen? = seen[id.value.substringAfter('/')]

    @Synchronized
    fun knows(chat: String) = chat in chats

    /** Names from the phone's Signal contacts, so chats and senders read as people. */
    @Synchronized
    fun learnNames(people: List<SigMember>) {
        people.forEach { if (it.name.isNotBlank() || it.phone.isNotBlank()) names[it.id] = it }
    }

    /** The contacts as people of this account, for the new-chat search. */
    @Synchronized
    fun lookupsJson(json: String): List<SigLookup> =
        sigJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SigLookup.serializer()), json)

    fun people(contacts: List<SigMember>): List<Person> =
        contacts.filter { !it.isMe && it.id != ownId && (it.name.isNotBlank() || it.phone.isNotBlank()) }.map(::person)

    /** Every chat known so far, as it reads now: sent again when names arrive. */
    @Synchronized
    fun allChats(): List<ChatSnapshot> = chats.values.map(::snapshot)

    /** A chat, remembered for later lookups. */
    @Synchronized
    fun chat(chat: SigChat): ChatSnapshot {
        chats[chat.id] = chat
        chat.members.forEach { if (it.name.isNotBlank() || it.phone.isNotBlank()) names[it.id] = it }
        return snapshot(chat)
    }

    /** The chat a message belongs to, made up when the phone's archive did not list it. */
    @Synchronized
    fun directChat(id: String): ChatSnapshot {
        val chat =
            chats[id] ?: SigChat(id, members = listOf(names[id] ?: SigMember(id))).also { chats[id] = it }
        return snapshot(chat)
    }

    @Synchronized
    fun message(msg: SigMessage): MessageSnapshot = snapshotOf(msg)

    /** Data events become connector events; control events return nothing. */
    @Synchronized
    fun translate(event: SigEvent): List<ConnectorEvent> =
        when (event) {
            is SigEvent.Chats -> event.chats.map { ConnectorEvent.ChatUpdated(accountId, chat(it)) }
            is SigEvent.Chat -> listOf(ConnectorEvent.ChatUpdated(accountId, chat(event.chat)))
            is SigEvent.Message -> messageEvents(event.message)
            is SigEvent.Receipt -> receiptEvents(event.receipt)
            is SigEvent.ReadSelf -> readSelfEvents(event)
            is SigEvent.Typing -> typingEvents(event)
            else -> emptyList()
        }

    private fun snapshot(chat: SigChat): ChatSnapshot {
        val others = chat.members.filter { !it.isMe && it.id != ownId }
        val title =
            when {
                chat.isGroup -> chat.name.ifBlank { others.joinToString { displayName(it) } }
                else -> chat.name.ifBlank { others.firstOrNull()?.let(::displayName) ?: "Signal" }
            }
        return ChatSnapshot(
            id = chatId(chat.id),
            accountId = accountId,
            kind = if (chat.isGroup) ChatKind.GROUP else ChatKind.DIRECT,
            title = title.ifBlank { "Signal" },
            participants = listOf(me()) + others.map(::person),
            unreadCount = chat.unread,
            lastActivityAt = Instant.fromEpochMilliseconds(chat.lastAt),
            folder = null,
            spaceId = null,
            networkRemoteId = chat.id,
        )
    }

    private fun me(): Person =
        Person(personId(ownId.ifEmpty { "me" }), accountId, "You", ownPhone.ifEmpty { null }, ownId, null, null)

    private val avatars = HashMap<String, String>()

    /** Keeps an avatar path seen on any listing of this person, so later views carry it too. */
    @Synchronized
    fun learnAvatars(members: List<SigMember>) {
        members.forEach { if (it.avatar.isNotEmpty()) avatars[it.id] = it.avatar }
    }

    private fun person(member: SigMember): Person =
        Person(
            id = personId(member.id),
            accountId = accountId,
            displayName = displayName(member),
            phoneNumber = member.phone.ifEmpty { null },
            networkHandle = member.phone.ifEmpty { member.id },
            avatarPath = member.avatar.ifEmpty { null } ?: avatars[member.id],
            contactId = null,
        )

    private fun displayName(member: SigMember): String =
        when {
            member.isMe || member.id == ownId -> "You"
            names[member.id]?.name?.isNotBlank() == true -> names.getValue(member.id).name
            member.name.isNotBlank() -> member.name
            member.phone.isNotBlank() -> member.phone
            else -> "Signal user"
        }

    private fun personOf(
        id: String,
        phone: String,
    ): Person =
        when {
            id == ownId -> me()
            else -> person(names[id] ?: SigMember(id, phone = phone))
        }

    private fun messageEvents(msg: SigMessage): List<ConnectorEvent> =
        when (msg.kind) {
            "reaction" -> {
                reactionEvents(msg)
            }

            "revoke" -> {
                revokeEvents(msg)
            }

            "edit" -> {
                editEvents(msg)
            }

            "typing" -> {
                listOf(
                    ConnectorEvent.Typing(
                        accountId,
                        chatId(msg.chat),
                        personId(msg.sender),
                        msg.typing ?: false,
                    ),
                )
            }

            "skip" -> {
                emptyList()
            }

            else -> {
                plainMessageEvents(msg)
            }
        }

    private fun reactionEvents(msg: SigMessage): List<ConnectorEvent> {
        val r = msg.reaction ?: return emptyList()
        val target = messageId(msg.chat, "${r.targetSender}:${r.targetTimestamp}")
        if (msg.fromMe || msg.sender == ownId) rememberOwnReaction(target, r.emoji, r.remove)
        val at = Instant.fromEpochMilliseconds(msg.timestamp)
        val reaction = Reaction(r.emoji, personId(msg.sender), at)
        return listOf(ConnectorEvent.ReactionChanged(accountId, target, reaction, removed = r.remove))
    }

    private fun revokeEvents(msg: SigMessage): List<ConnectorEvent> {
        val target = msg.revoke ?: return emptyList()
        val id = "${msg.sender}:${target.timestamp}"
        seen.remove("${msg.chat}/$id")
        return listOf(ConnectorEvent.MessageRevoked(accountId, chatId(msg.chat), messageId(msg.chat, id)))
    }

    private fun editEvents(msg: SigMessage): List<ConnectorEvent> {
        val edit = msg.edit ?: return emptyList()
        val at = Instant.fromEpochMilliseconds(msg.timestamp)
        val target = messageId(msg.chat, "${msg.sender}:${edit.targetTimestamp}")
        return listOf(ConnectorEvent.MessageEdited(accountId, chatId(msg.chat), target, edit.text, at))
    }

    private fun plainMessageEvents(msg: SigMessage): List<ConnectorEvent> {
        val before = seen["${msg.chat}/${msg.id}"]
        if (msg.senderPhone.isNotEmpty() && msg.sender !in names) {
            names[msg.sender] = SigMember(msg.sender, phone = msg.senderPhone)
        }
        val snapshot = snapshotOf(msg)
        // A chat the archive did not list (a brand-new conversation) is made up first.
        val chatEvents =
            if (msg.chat !in chats) listOf(ConnectorEvent.ChatUpdated(accountId, directChat(msg.chat))) else emptyList()
        val main =
            if (before != null) {
                ConnectorEvent.MessageUpdated(accountId, snapshot)
            } else {
                ConnectorEvent.NewMessage(accountId, snapshot)
            }
        return chatEvents + main
    }

    private fun snapshotOf(msg: SigMessage): MessageSnapshot {
        val fromMe = msg.fromMe || msg.sender == ownId
        val sender = if (fromMe) me() else personOf(msg.sender, msg.senderPhone)
        val sentAt = Instant.fromEpochMilliseconds(msg.timestamp)
        val attachment = attachmentOf(msg)
        val status = bestOf(seen["${msg.chat}/${msg.id}"]?.status, statusOf(msg, fromMe))
        remember(msg, fromMe, status)
        val message =
            Message(
                id = messageId(msg.chat, msg.id),
                chatId = chatId(msg.chat),
                senderId = sender.id,
                sentAt = sentAt,
                receivedAt = sentAt,
                body = bodyOf(msg),
                kind = kindOf(msg, attachment),
                attachments = listOfNotNull(attachment),
                replyTo = msg.quote?.let { messageId(msg.chat, it.id) },
                quote = msg.quote?.let { Quote(quoteName(it), it.text) },
                editedAt = null,
                deletedForEveryone = false,
                status = status,
                reactions =
                    msg.reactions.map {
                        Reaction(it.emoji, personId(it.sender), Instant.fromEpochMilliseconds(it.timestamp))
                    },
                transport = Transport.NETWORK,
                networkRemoteId = msg.id,
                linkPreview = null,
                isOutgoing = fromMe,
            )
        return MessageSnapshot(message, sender)
    }

    private fun quoteName(quote: SigQuote): String =
        when {
            quote.sender.isEmpty() -> ""
            quote.sender == ownId -> "You"
            else -> displayName(names[quote.sender] ?: SigMember(quote.sender))
        }

    private fun statusOf(
        msg: SigMessage,
        fromMe: Boolean,
    ): MessageStatus =
        when {
            !fromMe -> MessageStatus.Delivered
            msg.status == "read" -> MessageStatus.Read
            msg.status == "delivered" -> MessageStatus.Delivered
            else -> MessageStatus.Sent
        }

    private fun bodyOf(msg: SigMessage): String? =
        when (msg.kind) {
            "unsupported" -> UNSUPPORTED
            "sticker", "contact" -> msg.text.ifEmpty { null }
            else -> msg.text.ifEmpty { null }
        }

    private fun attachmentOf(msg: SigMessage): Attachment? {
        val media = msg.media ?: return null
        val kind =
            when (msg.kind) {
                "image" -> if (media.gif) AttachmentKind.GIF else AttachmentKind.IMAGE
                "video" -> AttachmentKind.VIDEO
                "voice" -> AttachmentKind.VOICE
                "audio" -> AttachmentKind.AUDIO
                "sticker" -> AttachmentKind.STICKER
                "document" -> AttachmentKind.FILE
                else -> return null
            }
        return Attachment(
            id = accountId.attachment("${msg.chat}/${msg.id}"),
            kind = kind,
            mimeType = media.mime.ifEmpty { "application/octet-stream" },
            fileName = media.fileName.ifEmpty { null },
            sizeBytes = media.size,
            localPath = null,
            remoteRef = sigJson.encodeToString(SigMedia.serializer(), media),
            durationMs = null,
            width = media.width.takeIf { it > 0 }?.toInt(),
            height = media.height.takeIf { it > 0 }?.toInt(),
            isEphemeral = msg.expiresIn > 0,
            savedAt = null,
        )
    }

    private fun kindOf(
        msg: SigMessage,
        attachment: Attachment?,
    ): MessageKind =
        when (attachment?.kind) {
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.VOICE, AttachmentKind.AUDIO -> MessageKind.VOICE
            AttachmentKind.STICKER -> MessageKind.STICKER
            AttachmentKind.FILE -> MessageKind.FILE
            else -> if (msg.kind == "contact") MessageKind.CONTACT else MessageKind.TEXT
        }

    private fun remember(
        msg: SigMessage,
        fromMe: Boolean,
        status: MessageStatus,
    ) {
        val key = "${msg.chat}/${msg.id}"
        seen.remove(key)
        seen[key] = Seen(msg.chat, msg.sender, fromMe, msg.timestamp, status)
        while (seen.size > REMEMBERED_MESSAGES) seen.remove(seen.keys.first())
    }

    /** A receipt names your messages by timestamp; the sender tells which chat for a DM. */
    private fun receiptEvents(receipt: SigReceipt): List<ConnectorEvent> {
        val status = if (receipt.kind == "delivered") MessageStatus.Delivered else MessageStatus.Read
        return receipt.timestamps.flatMap { ts ->
            seen.entries
                .filter { it.value.fromMe && it.value.timestamp == ts }
                .filter { it.value.chat == receipt.sender || it.value.chat.length == GROUP_ID_LENGTH }
                .map { (key, before) ->
                    val best = bestOf(before.status, status)
                    seen[key] = Seen(before.chat, before.sender, before.fromMe, before.timestamp, best)
                    ConnectorEvent.StatusChanged(accountId, accountId.message(key), best)
                }
        }
    }

    // Read on another of your devices: those chats are read there, so here too.
    private fun readSelfEvents(event: SigEvent.ReadSelf): List<ConnectorEvent> {
        val chatIds =
            event.messages
                .mapNotNull { mark ->
                    seen["${mark.sender}/${mark.sender}:${mark.timestamp}"]?.chat
                        ?: seen.entries
                            .firstOrNull {
                                it.key.endsWith(
                                    "/${mark.sender}:${mark.timestamp}",
                                )
                            }?.value
                            ?.chat
                        ?: mark.sender.takeIf { it in chats }
                }.toSet()
        return chatIds.mapNotNull { id ->
            val known = chats[id] ?: return@mapNotNull null
            val updated = known.copy(unread = 0)
            chats[id] = updated
            ConnectorEvent.ChatUpdated(accountId, snapshot(updated))
        }
    }

    private fun typingEvents(event: SigEvent.Typing): List<ConnectorEvent> =
        listOf(ConnectorEvent.Typing(accountId, chatId(event.chat), personId(event.sender), event.typing))

    companion object {
        private const val REMEMBERED_MESSAGES = 4000
        private const val GROUP_ID_LENGTH = 44
        const val UNSUPPORTED = "This kind of message is not supported yet"

        private val TICK_ORDER =
            listOf(MessageStatus.Sending, MessageStatus.Sent, MessageStatus.Delivered, MessageStatus.Read)

        private fun rank(status: MessageStatus) = TICK_ORDER.indexOf(status).coerceAtLeast(0)

        /** The further-along of two statuses; a failure always shows. */
        fun bestOf(
            before: MessageStatus?,
            now: MessageStatus,
        ): MessageStatus =
            if (before == null || now is MessageStatus.Failed || rank(now) >= rank(before)) now else before
    }
}
