// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp.bridge

import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.attachment
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.connector.space
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
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceKind
import org.pingme.core.model.Transport
import kotlin.time.Instant

/**
 * Turns what the Go bridge reports into PingMe's model for one account (BUILD_PLAN.md
 * Phase 6, network 1): chats, messages, and events into [ConnectorEvent]s. It remembers
 * every chat it has seen (for names and members), which messages it has seen (so a
 * repeat is an update, and a reaction or read receipt can name its message's sender),
 * and the account's own ids.
 *
 * Ids: a chat is its WhatsApp id ("<number>@s.whatsapp.net" or "<id>@g.us"); a message is
 * "<chat id>/<WhatsApp message id>", since WhatsApp ids are unique per chat only.
 *
 * Several threads call in (the event loop, the history worker, sends), so every public
 * method holds the one lock.
 */
@Suppress("TooManyFunctions") // One function per shape that crosses the bridge, plus the lookups the session needs.
class WaTranslate(
    private val accountId: AccountId,
    /** The name the phone's WhatsApp has for a user, or "". */
    private val contactName: (String) -> String = { "" },
    /** The phone number behind a hidden user id, or "". */
    private val phoneOf: (String) -> String = { "" },
) {
    private val chats = HashMap<String, WaChat>()
    private val names = HashMap<String, String>()

    /** What was last seen of each message, newest last; bounded, so memory stays flat. */
    private val seen = LinkedHashMap<String, Seen>()

    /** The newest messages of each chat, newest first, for history pages (bounded per chat). */
    private val recent = HashMap<String, MutableList<WaMessage>>()

    class Seen(
        val chat: String,
        val sender: String,
        val fromMe: Boolean,
        val timestamp: Long,
        val status: MessageStatus,
    )

    @Volatile var ownId: String = ""

    @Volatile var ownLid: String = ""

    @Volatile var ownPhone: String = ""

    fun parse(json: String): WaEvent = waJson.decodeFromString(WaEvent.serializer(), json)

    fun chatList(json: String): List<WaChat> =
        waJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WaChat.serializer()), json)

    fun chatJson(json: String): WaChat = waJson.decodeFromString(WaChat.serializer(), json)

    fun messageJson(json: String): WaMessage = waJson.decodeFromString(WaMessage.serializer(), json)

    fun idPairs(json: String): List<WaIdPair> =
        waJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WaIdPair.serializer()), json)

    fun participants(json: String): List<WaParticipant> =
        waJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WaParticipant.serializer()), json)

    /** Names from the phone's address book, so chats and senders read as people. */
    @Synchronized
    fun learnNames(people: List<WaParticipant>) {
        people.forEach { if (it.name.isNotBlank()) names[it.id] = it.name }
    }

    /** The phone's WhatsApp contacts as people of this account, for the new-chat search. */
    @Synchronized
    fun people(contacts: List<WaParticipant>): List<Person> =
        contacts
            .filter { it.name.isNotBlank() && !isMe(it.id) && !isPlaceholder(it.id) }
            // A contact known only by a hidden id is never listed by it (owner, Gate G7).
            .filter { it.phone.isNotBlank() || !it.id.endsWith(HIDDEN_ID_SUFFIX) }
            .map { person(it.id, it.name, it.phone) }

    /** Every chat known so far, as it reads now: sent again when names arrive. */
    @Synchronized
    fun allChats(): List<ChatSnapshot> = chats.values.filter { !it.isCommunity }.map { snapshot(it) }

    /**
     * An attachment's media reference with the message it came in, which asking the phone
     * for an expired file needs; older references name no message, so the id supplies it.
     */
    @Synchronized
    fun mediaRef(
        attachmentId: String,
        ref: String,
    ): String {
        val media = waJson.decodeFromString(WaMedia.serializer(), ref)
        if (media.messageId.isNotEmpty()) return ref
        val chat = attachmentId.substringBeforeLast('/')
        val id = attachmentId.substringAfterLast('/')
        val known = seen["$chat/$id"]
        val filled =
            media.copy(
                messageId = id,
                chat = chat,
                sender = known?.sender.orEmpty(),
                fromMe =
                    known?.fromMe ?: false,
            )
        return waJson.encodeToString(WaMedia.serializer(), filled)
    }

    @Synchronized
    fun knows(chat: String) = chat in chats

    /** The message a PingMe id names, if it has been seen. */
    @Synchronized
    fun seen(id: MessageId): Seen? = seen[remoteOf(id)]

    fun chatId(chat: String): ChatId = accountId.chat(chat)

    fun messageId(
        chat: String,
        id: String,
    ): MessageId = accountId.message("$chat/$id")

    fun personId(jid: String): PersonId = accountId.person(jid)

    /** Whether a user id is this account, by phone id or hidden id. */
    fun isMe(jid: String): Boolean = jid.isNotEmpty() && (jid == ownId || jid == ownLid)

    /** WhatsApp's "0" user stands for nobody (system notices); never a person. */
    fun isPlaceholder(jid: String): Boolean = jid.substringBefore('@') == "0"

    /** Data events become connector events; control events return nothing. */
    @Synchronized
    fun translate(event: WaEvent): List<ConnectorEvent> =
        when (event) {
            is WaEvent.Message -> messageEvents(event.message, fresh = true)
            is WaEvent.Receipt -> receiptEvents(event.receipt)
            is WaEvent.Typing -> typingEvents(event)
            is WaEvent.History -> historyEvents(event)
            is WaEvent.Group -> groupEvents(event.chat)
            is WaEvent.ChatRead -> chatReadEvents(event)
            else -> emptyList()
        }

    /** A group or community as a chat, remembered for later lookups. */
    @Synchronized
    fun chat(chat: WaChat): ChatSnapshot {
        chats[chat.id] = chat
        return snapshot(chat)
    }

    /** The groups in a community, as the space the community is (UI_DESIGN.md 10.4). */
    @Synchronized
    fun spaceOf(communityId: String): Space? {
        val community = chats[communityId] ?: return null
        val members = chats.values.filter { it.communityId == communityId && !it.isCommunity }.map { chatId(it.id) }
        return Space(
            accountId.space(communityId),
            accountId,
            community.name.ifBlank {
                "Community"
            },
            SpaceKind.WHATSAPP_COMMUNITY,
            members,
        )
    }

    /** The chat a one-to-one message belongs to, made up when the phone has not listed it. */
    @Synchronized
    fun directChat(jid: String): ChatSnapshot {
        val chat =
            chats[jid] ?: WaChat(jid, participants = listOf(WaParticipant(jid, phone = phoneDigits(jid)))).also {
                chats[jid] = it
            }
        return snapshot(chat)
    }

    @Synchronized
    fun message(msg: WaMessage): MessageSnapshot = snapshotOf(msg)

    private fun snapshot(chat: WaChat): ChatSnapshot {
        val others = chat.participants.filter { !it.isMe && !isMe(it.id) }
        val people =
            buildList {
                add(me())
                others.filter { !isPlaceholder(it.id) }.forEach { add(person(it.id, it.name, it.phone)) }
            }
        val title =
            when {
                chat.isGroup -> chat.name.ifBlank { others.joinToString { displayName(it.id, it.name, it.phone) } }
                else -> displayName(chat.id, chat.name, phoneDigits(chat.id))
            }
        return ChatSnapshot(
            id = chatId(chat.id),
            accountId = accountId,
            kind = if (chat.isGroup) ChatKind.GROUP else ChatKind.DIRECT,
            title = title,
            participants = people,
            unreadCount = chat.unread,
            lastActivityAt = Instant.fromEpochMilliseconds(chat.lastMessageAt),
            folder = null,
            spaceId = chat.communityId.takeIf { it.isNotEmpty() }?.let { accountId.space(it) },
            networkRemoteId = chat.id,
        )
    }

    private fun me(): Person =
        Person(
            personId(
                ownId.ifEmpty {
                    "me"
                },
            ),
            accountId,
            "You",
            ownPhone.takeIf { it.isNotEmpty() }?.let { "+$it" },
            ownId,
            null,
            null,
        )

    private fun person(
        jid: String,
        name: String,
        phone: String,
    ): Person {
        val digits = phone.ifEmpty { phoneDigits(jid) }
        return Person(
            id = personId(jid),
            accountId = accountId,
            displayName = displayName(jid, name, digits),
            phoneNumber = digits.takeIf { it.isNotEmpty() }?.let { "+$it" },
            // The number is what a person goes by here; the raw id only when there is none.
            networkHandle = digits.takeIf { it.isNotEmpty() }?.let { "+$it" } ?: jid,
            avatarPath = null,
            contactId = null,
        )
    }

    // The address book's name, then what the chat says, then the push name, then the number.
    private fun displayName(
        jid: String,
        given: String,
        phone: String,
    ): String {
        val known = names[jid] ?: contactName(jid).takeIf { it.isNotBlank() }?.also { names[jid] = it }
        val digits = phone.ifEmpty { phoneDigits(jid) }
        return when {
            isMe(jid) -> "You"
            known != null -> known
            given.isNotBlank() -> given
            digits.isNotEmpty() -> "+$digits"
            else -> jid.substringBefore('@')
        }
    }

    // The digits of a phone-form user id; a hidden id resolves through the phone when it can.
    private fun phoneDigits(jid: String): String =
        when {
            jid.endsWith("@s.whatsapp.net") -> jid.substringBefore('@').substringBefore(':')
            jid.endsWith("@lid") -> phoneOf(jid)
            else -> ""
        }

    private fun messageEvents(
        msg: WaMessage,
        fresh: Boolean,
    ): List<ConnectorEvent> {
        val chatId = chatId(msg.chat)
        return when (msg.kind) {
            "reaction" -> {
                msg.reaction
                    ?.let { r ->
                        val target = messageId(msg.chat, r.targetId)
                        val at = Instant.fromEpochMilliseconds(msg.timestamp)
                        // Taken away: the emoji is empty; the store drops the sender's reaction.
                        val reaction = Reaction(r.emoji, personId(senderOf(msg)), at)
                        listOf(ConnectorEvent.ReactionChanged(accountId, target, reaction, removed = r.emoji.isEmpty()))
                    }.orEmpty()
            }

            "revoke" -> {
                msg.revoke
                    ?.let { target ->
                        seen.remove("${msg.chat}/${target.id}")
                        listOf(ConnectorEvent.MessageRevoked(accountId, chatId, messageId(msg.chat, target.id)))
                    }.orEmpty()
            }

            "edit" -> {
                msg.edit
                    ?.let { edit ->
                        val at = Instant.fromEpochMilliseconds(msg.timestamp)
                        val target = messageId(msg.chat, edit.targetId)
                        listOf(ConnectorEvent.MessageEdited(accountId, chatId, target, edit.text, at))
                    }.orEmpty()
            }

            "system", "skip" -> {
                emptyList()
            }

            else -> {
                val before = seen["${msg.chat}/${msg.id}"]
                val snapshot = snapshotOf(msg)
                val chatEvents =
                    if (!msg.chat.endsWith("@g.us") && msg.chat !in chats) {
                        listOf(ConnectorEvent.ChatUpdated(accountId, directChat(msg.chat)))
                    } else {
                        emptyList()
                    }
                val main =
                    if (before != null || !fresh) {
                        ConnectorEvent.MessageUpdated(accountId, snapshot)
                    } else {
                        ConnectorEvent.NewMessage(accountId, snapshot)
                    }
                chatEvents + main
            }
        }
    }

    private fun senderOf(msg: WaMessage): String = if (msg.fromMe) ownId.ifEmpty { msg.sender } else msg.sender

    private fun snapshotOf(msg: WaMessage): MessageSnapshot {
        val id = messageId(msg.chat, msg.id)
        val sentAt = Instant.fromEpochMilliseconds(msg.timestamp)
        val senderJid = senderOf(msg)
        val sender = if (msg.fromMe) me() else person(senderJid, msg.pushName, msg.senderPhone)
        val attachment = attachmentOf(msg)
        val status = bestOf(seen["${msg.chat}/${msg.id}"]?.status, statusOf(msg))
        remember(msg, status)
        val message =
            Message(
                id = id,
                chatId = chatId(msg.chat),
                senderId = sender.id,
                sentAt = sentAt,
                receivedAt = sentAt,
                body = bodyOf(msg),
                kind = kindOf(msg, attachment),
                attachments = listOfNotNull(attachment),
                replyTo = msg.replyTo?.let { messageId(msg.chat, it.id) },
                quote = msg.replyTo?.let { Quote(quoteName(it), it.text) },
                editedAt = if (msg.edited) sentAt else null,
                deletedForEveryone = false,
                status = status,
                reactions = emptyList(),
                transport = Transport.NETWORK,
                networkRemoteId = msg.id,
                linkPreview = null,
                isOutgoing = msg.fromMe,
            )
        return MessageSnapshot(message, sender)
    }

    private fun quoteName(quote: WaQuote): String =
        when {
            quote.sender.isEmpty() -> ""
            isMe(quote.sender) -> "You"
            else -> displayName(quote.sender, "", "")
        }

    private fun bodyOf(msg: WaMessage): String? =
        when (msg.kind) {
            "unsupported" -> UNSUPPORTED
            "location" -> msg.location?.name?.takeIf { it.isNotBlank() }
            "contact" -> null
            else -> msg.text.ifEmpty { null }
        }

    private fun attachmentOf(msg: WaMessage): Attachment? {
        val kind = ATTACHMENT_KINDS[msg.kind] ?: return null
        val media = msg.media
        return Attachment(
            id = accountId.attachment("${msg.chat}/${msg.id}"),
            kind = kind,
            mimeType = mimeOf(kind, media),
            fileName = fileNameOf(kind, msg),
            sizeBytes = media?.fileLength ?: 0,
            localPath = null,
            remoteRef = remoteRefOf(kind, msg),
            durationMs = media?.seconds?.takeIf { it > 0 }?.let { it * MILLIS },
            width = media?.width?.takeIf { it > 0 }?.toInt(),
            height = media?.height?.takeIf { it > 0 }?.toInt(),
            isEphemeral = msg.isViewOnce,
            savedAt = null,
        )
    }

    private fun mimeOf(
        kind: AttachmentKind,
        media: WaMedia?,
    ): String =
        when (kind) {
            AttachmentKind.LOCATION -> "application/geo+json"
            AttachmentKind.CONTACT -> "text/vcard"
            else -> media?.mime?.substringBefore(';')?.trim() ?: "application/octet-stream"
        }

    private fun fileNameOf(
        kind: AttachmentKind,
        msg: WaMessage,
    ): String? =
        when (kind) {
            AttachmentKind.CONTACT -> "${msg.contact?.displayName.orEmpty().ifBlank { "contact" }}.vcf"
            else -> msg.media?.fileName?.ifEmpty { null }
        }

    // What a download needs: the media's keys, or the place or card the message itself carried.
    private fun remoteRefOf(
        kind: AttachmentKind,
        msg: WaMessage,
    ): String? =
        when (kind) {
            AttachmentKind.LOCATION -> msg.location?.let { waJson.encodeToString(WaLocation.serializer(), it) }
            AttachmentKind.CONTACT -> msg.contact?.let { waJson.encodeToString(WaContact.serializer(), it) }
            else -> msg.media?.let { waJson.encodeToString(WaMedia.serializer(), it) }
        }

    private fun kindOf(
        msg: WaMessage,
        attachment: Attachment?,
    ): MessageKind =
        when (attachment?.kind) {
            null -> MessageKind.TEXT
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.VOICE, AttachmentKind.AUDIO -> MessageKind.VOICE
            AttachmentKind.FILE -> MessageKind.FILE
            AttachmentKind.STICKER -> MessageKind.STICKER
            AttachmentKind.LOCATION -> MessageKind.LOCATION
            AttachmentKind.CONTACT -> MessageKind.CONTACT
        }.also { if (msg.kind == "unsupported") return MessageKind.TEXT }

    private fun statusOf(msg: WaMessage): MessageStatus =
        when {
            !msg.fromMe -> MessageStatus.Delivered
            msg.status == "read" || msg.status == "played" -> MessageStatus.Read
            msg.status == "delivered" -> MessageStatus.Delivered
            msg.status == "error" -> MessageStatus.Failed("Not sent")
            msg.status == "pending" -> MessageStatus.Sending
            else -> MessageStatus.Sent
        }

    private fun remember(
        msg: WaMessage,
        status: MessageStatus,
    ) {
        val key = "${msg.chat}/${msg.id}"
        seen.remove(key)
        seen[key] = Seen(msg.chat, senderOf(msg), msg.fromMe, msg.timestamp, status)
        while (seen.size > REMEMBERED_MESSAGES) seen.remove(seen.keys.first())
        val list = recent.getOrPut(msg.chat) { mutableListOf() }
        list.removeAll { it.id == msg.id }
        val at = list.indexOfFirst { it.timestamp < msg.timestamp }
        list.add(if (at < 0) list.size else at, msg)
        while (list.size > RECENT_PER_CHAT) list.removeAt(list.size - 1)
    }

    /**
     * A page of this session's remembered messages for [chat], newest first: the newest
     * [limit] when [before] is null, or the ones older than [before]. Null when [before] is
     * not remembered, so the caller asks the phone instead.
     */
    @Synchronized
    fun recentPage(
        chat: String,
        before: String?,
        limit: Int,
    ): List<MessageSnapshot>? {
        val list = recent[chat].orEmpty()
        val from =
            if (before == null) {
                0
            } else {
                val at = list.indexOfFirst { it.id == before }
                if (at < 0) return null
                at + 1
            }
        return list.drop(from).take(limit).map { snapshotOf(it) }
    }

    private fun receiptEvents(receipt: WaReceipt): List<ConnectorEvent> {
        if (receipt.fromMe) {
            // Read on another of your devices: the chat is read there, so here too.
            val chat = chats[receipt.chat] ?: return emptyList()
            return listOf(ConnectorEvent.ChatUpdated(accountId, snapshot(chat.copy(unread = 0))))
        }
        val status =
            when (receipt.kind) {
                "read", "played" -> MessageStatus.Read
                else -> MessageStatus.Delivered
            }
        return receipt.ids.mapNotNull { id ->
            val key = "${receipt.chat}/$id"
            val before = seen[key]
            // Only our own messages get ticks, and a tick never goes backwards.
            if (before != null && !before.fromMe) return@mapNotNull null
            val best = bestOf(before?.status, status)
            if (before != null) seen[key] = Seen(before.chat, before.sender, before.fromMe, before.timestamp, best)
            ConnectorEvent.StatusChanged(accountId, messageId(receipt.chat, id), best)
        }
    }

    private fun typingEvents(event: WaEvent.Typing): List<ConnectorEvent> =
        listOf(
            ConnectorEvent.Typing(
                accountId,
                chatId(event.chat),
                personId(
                    event.sender.ifEmpty {
                        event.chat
                    },
                ),
                event.typing,
            ),
        )

    private fun chatReadEvents(event: WaEvent.ChatRead): List<ConnectorEvent> {
        val chat = chats[event.chat] ?: return emptyList()
        val unread = if (event.read) 0 else maxOf(1, chat.unread)
        chats[event.chat] = chat.copy(unread = unread)
        return listOf(ConnectorEvent.ChatUpdated(accountId, snapshot(chat.copy(unread = unread))))
    }

    /**
     * A history page: the chat, then its messages as one batch, then the reactions,
     * revokes, and edits among them, which refer to messages now stored.
     */
    private fun historyEvents(event: WaEvent.History): List<ConnectorEvent> {
        val chat = event.chat
        if (chat.isCommunity) {
            chats[chat.id] = chat
            return listOfNotNull(spaceOf(chat.id)?.let { ConnectorEvent.SpaceUpdated(accountId, it) })
        }
        val known = chats[chat.id]
        // The groups listing knows members and the community; a history page knows unread and time.
        val merged =
            if (known != null && chat.isGroup) {
                known.copy(
                    unread = chat.unread,
                    lastMessageAt = maxOf(known.lastMessageAt, chat.lastMessageAt),
                    archived = chat.archived,
                )
            } else {
                chat
            }
        chats[chat.id] = merged
        val plain = event.messages.filter { it.kind !in REFERRING && it.kind !in SILENT }
        val referring = event.messages.filter { it.kind in REFERRING }
        val batch =
            ConnectorEvent.HistoryBatch(
                accountId,
                chatId(chat.id),
                plain.map { snapshotOf(it) },
                complete = false,
            )
        return listOf(ConnectorEvent.ChatUpdated(accountId, snapshot(merged)), batch) +
            referring.sortedBy { it.timestamp }.flatMap { messageEvents(it, fresh = false) }
    }

    private fun groupEvents(chat: WaChat): List<ConnectorEvent> {
        val known = chats[chat.id]
        val merged =
            if (known !=
                null
            ) {
                chat.copy(unread = known.unread, lastMessageAt = maxOf(known.lastMessageAt, chat.lastMessageAt))
            } else {
                chat
            }
        chats[chat.id] = merged
        if (chat.isCommunity) {
            return listOfNotNull(spaceOf(chat.id)?.let { ConnectorEvent.SpaceUpdated(accountId, it) })
        }
        val space = chat.communityId.takeIf { it.isNotEmpty() }?.let { spaceOf(it) }
        return listOf(ConnectorEvent.ChatUpdated(accountId, snapshot(merged))) +
            listOfNotNull(space?.let { ConnectorEvent.SpaceUpdated(accountId, it) })
    }

    /** Messages of a history page for the history worker: the plain ones, newest first. */
    @Synchronized
    fun historyPage(event: WaEvent.History): List<MessageSnapshot> {
        chats.putIfAbsent(event.chat.id, event.chat)
        return event.messages
            .filter { it.kind !in REFERRING && it.kind !in SILENT }
            .sortedByDescending { it.timestamp }
            .map { snapshotOf(it) }
    }

    private fun remoteOf(id: MessageId): String = id.value.substringAfter('/')

    companion object {
        private const val REMEMBERED_MESSAGES = 4000
        private const val RECENT_PER_CHAT = 300
        private const val MILLIS = 1000L
        private val REFERRING = setOf("reaction", "revoke", "edit")

        /** Kinds that are nothing to show: group notices and housekeeping between devices. */
        private val SILENT = setOf("system", "skip")
        private val ATTACHMENT_KINDS =
            mapOf(
                "image" to AttachmentKind.IMAGE,
                "gif" to AttachmentKind.GIF,
                "video" to AttachmentKind.VIDEO,
                "voice" to AttachmentKind.VOICE,
                "audio" to AttachmentKind.AUDIO,
                "document" to AttachmentKind.FILE,
                "sticker" to AttachmentKind.STICKER,
                "location" to AttachmentKind.LOCATION,
                "contact" to AttachmentKind.CONTACT,
            )
        const val UNSUPPORTED = "This kind of message is not supported yet"

        /** The further-along of two statuses; a failure always shows. */
        fun bestOf(
            before: MessageStatus?,
            now: MessageStatus,
        ): MessageStatus =
            if (before == null || now is MessageStatus.Failed || rank(now) >= rank(before)) now else before

        private val TICK_ORDER =
            listOf(MessageStatus.Sending, MessageStatus.Sent, MessageStatus.Delivered, MessageStatus.Read)

        private fun rank(status: MessageStatus) = TICK_ORDER.indexOf(status).coerceAtLeast(0)
    }
}

/** WhatsApp's hidden user ids end this way. */
private const val HIDDEN_ID_SUFFIX = "@lid"
