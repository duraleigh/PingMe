// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gvoice.bridge

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
import org.pingme.core.model.Transport
import kotlin.time.Instant

/**
 * Turns what the Go bridge reports into PingMe's model for one Google Voice account
 * (BUILD_PLAN.md Phase 6, network 4): threads into chats, items into messages, events
 * into [ConnectorEvent]s. It remembers threads and names, so a repeat is an update.
 *
 * Ids: a chat is its thread id; a message is "<thread>/<item id>"; a person is their
 * phone number.
 */
@Suppress("TooManyFunctions") // One function per shape that crosses the bridge, plus the lookups the session needs.
class GvTranslate(
    private val accountId: AccountId,
    /** The name Google has for a number, or "". */
    private val contactName: (String) -> String = { "" },
) {
    private val threads = HashMap<String, GvThread>()
    private val names = HashMap<String, String>()

    @Volatile var ownPhone: String = ""

    fun parse(json: String): GvEvent = gvJson.decodeFromString(GvEvent.serializer(), json)

    fun threadJson(json: String): GvThread = gvJson.decodeFromString(GvThread.serializer(), json)

    fun threadsJson(json: String): List<GvThread> =
        gvJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GvThread.serializer()), json)

    fun messageJson(json: String): GvMessage = gvJson.decodeFromString(GvMessage.serializer(), json)

    fun chatId(thread: String): ChatId = accountId.chat(thread)

    fun messageId(
        thread: String,
        id: String,
    ): MessageId = accountId.message("$thread/$id")

    fun personId(phone: String): PersonId = accountId.person(phone)

    @Synchronized
    fun knows(thread: String) = thread in threads

    @Synchronized
    fun learnNames(people: List<GvMember>) {
        people.forEach { if (it.name.isNotBlank()) names[it.phone] = it.name }
    }

    @Synchronized
    fun people(contacts: List<GvMember>): List<Person> =
        contacts.filter { it.name.isNotBlank() && it.phone != ownPhone }.distinctBy { it.phone }.map {
            person(
                it.phone,
            )
        }

    /** Data events become connector events; control events return nothing. */
    @Synchronized
    fun translate(event: GvEvent): List<ConnectorEvent> =
        when (event) {
            is GvEvent.Thread -> threadEvents(event.thread)
            is GvEvent.Message -> messageEvents(event.message)
            else -> emptyList()
        }

    /** A thread as a chat, remembered for later lookups. */
    @Synchronized
    fun chat(thread: GvThread): ChatSnapshot {
        val known = threads[thread.id]
        // A listing without messages keeps the newest ones already seen.
        val merged = if (thread.messages.isEmpty() && known != null) thread.copy(messages = known.messages) else thread
        threads[thread.id] = merged
        thread.contacts.forEach { if (it.name.isNotBlank()) names[it.phone] = it.name }
        return snapshot(merged)
    }

    /** The chat for a number, made up before Google Voice has listed it. */
    @Synchronized
    fun directChat(thread: String): ChatSnapshot {
        val known =
            threads[thread]
                ?: GvThread(thread, phoneNumbers = listOf(thread.removePrefix("t."))).also { threads[thread] = it }
        return snapshot(known)
    }

    @Synchronized
    fun message(msg: GvMessage): MessageSnapshot = snapshotOf(msg)

    private fun snapshot(thread: GvThread): ChatSnapshot {
        val others = thread.phoneNumbers.filter { it != ownPhone }
        return ChatSnapshot(
            id = chatId(thread.id),
            accountId = accountId,
            kind = if (others.size > 1) ChatKind.GROUP else ChatKind.DIRECT,
            title = others.joinToString { displayName(it) }.ifBlank { "Google Voice" },
            participants = listOf(me()) + others.map(::person),
            unreadCount = if (thread.read) 0 else 1,
            lastActivityAt = Instant.fromEpochMilliseconds(thread.lastAt),
            folder = null,
            spaceId = null,
            networkRemoteId = thread.id,
        )
    }

    private fun me(): Person =
        Person(
            personId(
                ownPhone.ifEmpty {
                    "me"
                },
            ),
            accountId,
            "You",
            ownPhone.ifEmpty { null },
            ownPhone,
            null,
            null,
        )

    private fun person(phone: String): Person =
        Person(personId(phone), accountId, displayName(phone), phone, phone, null, null)

    private fun displayName(phone: String): String {
        if (phone == ownPhone) return "You"
        val known = names[phone] ?: contactName(phone).takeIf { it.isNotBlank() }?.also { names[phone] = it }
        return known ?: phone
    }

    private fun threadEvents(thread: GvThread): List<ConnectorEvent> {
        val chat = chat(thread)
        if (thread.messages.isEmpty()) return listOf(ConnectorEvent.ChatUpdated(accountId, chat))
        val batch =
            ConnectorEvent.HistoryBatch(
                accountId,
                chat.id,
                thread.messages.map { snapshotOf(it) },
                complete = false,
            )
        return listOf(ConnectorEvent.ChatUpdated(accountId, chat), batch)
    }

    private fun messageEvents(msg: GvMessage): List<ConnectorEvent> {
        val chatEvents =
            if (msg.thread !in
                threads
            ) {
                listOf(ConnectorEvent.ChatUpdated(accountId, directChat(msg.thread)))
            } else {
                emptyList()
            }
        return chatEvents + ConnectorEvent.NewMessage(accountId, snapshotOf(msg))
    }

    private fun snapshotOf(msg: GvMessage): MessageSnapshot {
        val sender = if (msg.fromMe) me() else person(msg.sender.ifEmpty { msg.thread.removePrefix("t.") })
        val sentAt = Instant.fromEpochMilliseconds(msg.timestamp)
        val attachments = attachmentsOf(msg)
        val message =
            Message(
                id = messageId(msg.thread, msg.id),
                chatId = chatId(msg.thread),
                senderId = sender.id,
                sentAt = sentAt,
                receivedAt = sentAt,
                body = bodyOf(msg, attachments),
                kind = kindOf(attachments),
                attachments = attachments,
                replyTo = null,
                quote = null,
                editedAt = null,
                deletedForEveryone = false,
                status = if (msg.fromMe) MessageStatus.Sent else MessageStatus.Delivered,
                reactions = emptyList(),
                transport = Transport.SMS,
                networkRemoteId = msg.id,
                linkPreview = null,
                isOutgoing = msg.fromMe,
            )
        return MessageSnapshot(message, sender)
    }

    private fun bodyOf(
        msg: GvMessage,
        attachments: List<Attachment>,
    ): String? =
        when {
            msg.kind == "unsupported" -> msg.text.ifEmpty { UNSUPPORTED }
            msg.text.isNotEmpty() -> msg.text
            attachments.isEmpty() && msg.media.any { it.unsupported } -> "A file Google Voice could not take"
            else -> null
        }

    private fun attachmentsOf(msg: GvMessage): List<Attachment> =
        msg.media.filter { !it.unsupported && it.id.isNotEmpty() }.mapIndexed { i, media ->
            val mime = media.mime.ifEmpty { "image/jpeg" }
            val kind =
                when {
                    mime == "image/gif" -> AttachmentKind.GIF
                    mime.startsWith("image/") -> AttachmentKind.IMAGE
                    mime.startsWith("video/") -> AttachmentKind.VIDEO
                    mime.startsWith("audio/") -> AttachmentKind.AUDIO
                    else -> AttachmentKind.FILE
                }
            Attachment(
                id = accountId.attachment("${msg.thread}/${msg.id}/$i"),
                kind = kind,
                mimeType = mime,
                fileName = null,
                sizeBytes = 0,
                localPath = null,
                remoteRef = gvJson.encodeToString(GvMedia.serializer(), media),
                durationMs = null,
                width = media.width.takeIf { it > 0 },
                height = media.height.takeIf { it > 0 },
                isEphemeral = false,
                savedAt = null,
            )
        }

    private fun kindOf(attachments: List<Attachment>): MessageKind =
        when (attachments.firstOrNull()?.kind) {
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.AUDIO -> MessageKind.VOICE
            AttachmentKind.FILE -> MessageKind.FILE
            else -> MessageKind.TEXT
        }

    companion object {
        const val UNSUPPORTED = "This kind of message is not supported yet"
    }
}
