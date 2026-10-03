// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.pingme.connectors.signal.bridge.SigBridge
import org.pingme.connectors.signal.bridge.SigError
import org.pingme.connectors.signal.bridge.SigEvent
import org.pingme.connectors.signal.bridge.SigMedia
import org.pingme.connectors.signal.bridge.SigMember
import org.pingme.connectors.signal.bridge.SigQuote
import org.pingme.connectors.signal.bridge.SigSession
import org.pingme.connectors.signal.bridge.SigTranslate
import org.pingme.connectors.signal.bridge.sigJson
import org.pingme.core.connector.ActionNeededException
import org.pingme.core.connector.AddressBookEntry
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.connector.attachment
import org.pingme.core.connector.remoteId
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One linked Signal account's live connection (BUILD_PLAN.md Phase 6, network 3): the
 * bridge session, the event loop that turns Signal's events into [ConnectorEvent]s, and
 * every request on it. The chat list and older messages come from the history the phone
 * transferred at link time; everything after that is live.
 */
@Suppress("TooManyFunctions") // One function per thing the connector can ask of Signal.
internal class SignalSession(
    val accountId: AccountId,
    bridge: SigBridge,
    dbPath: String,
) {
    private val events = Channel<Any>(Channel.UNLIMITED)
    val go = SigTranslate(accountId)
    private val session: SigSession =
        bridge.newSession(dbPath) { json ->
            try {
                events.trySend(go.parse(json))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Unreadable event from the bridge", e)
            }
        }
    private val connected = AtomicBoolean(false)

    init {
        go.ownId = session.ownId()
        go.ownPhone = session.ownPhone()
    }

    /** Connects, then streams events until [close] or a failure. */
    fun flow(): Flow<ConnectorEvent> =
        channelFlow {
            try {
                request { session.connect() }
                for (event in events) {
                    when (event) {
                        is SigEvent -> handle(event)
                        is ConnectorEvent -> send(event)
                    }
                }
            } finally {
                session.disconnect()
            }
        }

    fun close() {
        events.close()
        session.close()
    }

    private suspend fun ProducerScope<ConnectorEvent>.handle(event: SigEvent) {
        when (event) {
            is SigEvent.Connected -> {
                go.ownId = event.id.ifEmpty { go.ownId }
                go.ownPhone = event.phone.ifEmpty { go.ownPhone }
                learnNames()
                if (!connected.getAndSet(true)) send(ConnectorEvent.State(accountId, ConnectionState.Connected))
            }

            is SigEvent.Transfer -> {
                Log.i(TAG, "History transfer from the phone: ${event.state} ${event.reason}")
            }

            is SigEvent.Contacts -> {
                learnNames()
                go.allChats().forEach { send(ConnectorEvent.ChatUpdated(accountId, it)) }
            }

            is SigEvent.LoggedOut -> {
                throw ActionNeededException(
                    "Signal has unlinked PingMe (${event.reason}). Link it again.",
                    SIGNAL_PACKAGE,
                )
            }

            is SigEvent.ConnectError -> {
                if (event.fatal) throw IOException("Signal connection failed (${event.reason}); reconnecting")
                Log.w(TAG, "Signal connection trouble: ${event.reason}")
            }

            is SigEvent.Disconnected -> {
                Log.i(TAG, "Signal connection dropped; the bridge reconnects by itself")
            }

            is SigEvent.Undecryptable -> {
                Log.w(TAG, "A message from ${event.sender} could not be decrypted")
            }

            else -> {
                go.translate(event).forEach { send(it) }
            }
        }
    }

    private suspend fun ProducerScope<ConnectorEvent>.learnNames() {
        try {
            val contacts = go.membersJson(request { session.contacts() })
            go.learnNames(contacts)
            send(ConnectorEvent.PeopleUpdated(accountId, go.people(contacts)))
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Could not read Signal's contacts", e)
        }
    }

    /** The chats the phone's history holds. */
    suspend fun syncChats(): List<ChatSnapshot> = go.chatsJson(request { session.chats() }).map { go.chat(it) }

    /** Up to [limit] messages older than [before] (the newest when null), newest first. */
    suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> {
        val chat = chatId.remoteId
        val anchor = before?.remoteId?.substringAfterLast(':')?.toLongOrNull() ?: 0L
        return go
            .messagesJson(request { session.messages(chat, anchor, limit) })
            .filter { it.kind !in REFERRING && it.kind != "skip" && it.timestamp != anchor }
            .sortedByDescending { it.timestamp }
            .map { go.message(it.copy(chat = it.chat.ifEmpty { chat })) }
    }

    suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
        progress: (Float) -> Unit,
    ): SendResult {
        val chat = chatId.remoteId
        val quote = draft.replyTo?.let { quoteJson(it, draft) }.orEmpty()
        return try {
            val files = draft.attachments
            if (files.isEmpty()) {
                val json = request { session.sendText(chat, draft.body.orEmpty(), quote) }
                SendResult.Sent(go.message(go.messageJson(json).copy(chat = chat)))
            } else {
                // One file per message; the text rides as the first file's caption.
                var first: MessageSnapshot? = null
                files.forEachIndexed { i, file ->
                    val caption = if (i == 0) draft.body.orEmpty() else ""
                    val json =
                        request {
                            session.sendMedia(
                                chat,
                                file.localPath,
                                file.mimeType,
                                file.fileName.orEmpty(),
                                caption,
                                file.kind == AttachmentKind.VOICE,
                                if (i == 0) quote else "",
                            )
                        }
                    val sent = go.message(go.messageJson(json).copy(chat = chat)).keepingFile(file)
                    progress((i + 1).toFloat() / files.size)
                    if (first == null) first = sent else events.trySend(ConnectorEvent.NewMessage(accountId, sent))
                }
                SendResult.Sent(requireNotNull(first))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "send to $chat failed", e)
            SendResult.Failed(sendFailure(e), retryable = SigError.codeOf(e) != SigError.REJECTED)
        }
    }

    private fun quoteJson(
        replyTo: MessageId,
        draft: OutgoingMessage,
    ): String {
        val seen = go.seen(replyTo)
        val quote = SigQuote(replyTo.remoteId.substringAfter('/'), seen?.sender.orEmpty(), draft.quote?.text.orEmpty())
        return sigJson.encodeToString(SigQuote.serializer(), quote)
    }

    private fun MessageSnapshot.keepingFile(file: OutgoingAttachment): MessageSnapshot {
        val kept =
            Attachment(
                id = accountId.attachment("${message.chatId.remoteId}/${message.networkRemoteId}"),
                kind = file.kind,
                mimeType = file.mimeType,
                fileName = file.fileName,
                sizeBytes = File(file.localPath).length(),
                localPath = file.localPath,
                remoteRef = message.attachments.firstOrNull()?.remoteRef,
                durationMs = file.durationMs,
                width = null,
                height = null,
                isEphemeral = false,
                savedAt = null,
            )
        return copy(message = message.copy(attachments = listOf(kept), kind = messageKindOf(file.kind)))
    }

    suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) {
        val seen = go.seen(messageId) ?: throw UnsupportedCapabilityException("Wait for the message to finish sending")
        // Signal takes a reaction away by naming it again, so yours is remembered.
        val named = emoji ?: go.ownReaction(messageId) ?: return
        request { session.sendReaction(seen.chat, messageId.remoteId.substringAfter('/'), named, remove) }
        go.rememberOwnReaction(messageId, named, remove)
    }

    suspend fun revoke(messageId: MessageId) {
        val seen =
            go.seen(messageId) ?: throw UnsupportedCapabilityException("This message cannot be deleted for everyone")
        request { session.revoke(seen.chat, messageId.remoteId.substringAfter('/')) }
    }

    suspend fun edit(
        messageId: MessageId,
        text: String,
    ) {
        val seen = go.seen(messageId) ?: throw UnsupportedCapabilityException("This message cannot be edited")
        request { session.edit(seen.chat, messageId.remoteId.substringAfter('/'), text) }
    }

    suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) {
        val ids =
            sigJson.encodeToString(
                ListSerializer(String.serializer()),
                listOf(upTo.remoteId.substringAfter('/')),
            )
        request { session.markRead(chatId.remoteId, ids) }
    }

    suspend fun typing(
        chatId: ChatId,
        typing: Boolean,
    ) = request { session.setTyping(chatId.remoteId, typing) }

    /**
     * The phone's contacts who are on Signal, the way Signal's own app lists them: every
     * number in the address book asked of Signal's directory, in batches (owner, Gate G7).
     * The ones found become this account's people, named from the address book.
     */
    suspend fun refreshPeople(book: List<AddressBookEntry>) {
        val byNumber = LinkedHashMap<String, String>()
        book.forEach { entry ->
            entry.phones.forEach { raw ->
                normalizeNumber(raw, go.ownPhone)?.let { byNumber.putIfAbsent(it, entry.name) }
            }
        }
        if (byNumber.isEmpty()) return
        val json = sigJson.encodeToString(ListSerializer(String.serializer()), byNumber.keys.toList())
        val found = go.lookupsJson(request { session.lookupNumbers(json) })
        val members = found.map { SigMember(it.id, it.phone, byNumber[it.phone].orEmpty()) }
        go.learnNames(members)
        events.trySend(ConnectorEvent.PeopleUpdated(accountId, go.people(members)))
    }

    /** The chat for a phone number, once Signal says the number has an account. */
    suspend fun startConversation(phone: String): ChatId {
        val id = request { session.checkNumber(phone) }
        if (id.isEmpty()) throw UnsupportedCapabilityException("$phone is not on Signal")
        go.directChat(id)
        events.trySend(ConnectorEvent.ChatUpdated(accountId, go.directChat(id)))
        return go.chatId(id)
    }

    suspend fun download(
        attachment: Attachment,
        target: File,
    ): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        val ref = requireNotNull(attachment.remoteRef) { "nothing to fetch" }
        sigJson.decodeFromString(SigMedia.serializer(), ref) // checked shape
        request { session.download(ref, target.absolutePath) }
        return target
    }

    /** Runs a bridge call off the main thread; an unlinked answer also ends the session. */
    private suspend fun <T> request(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (SigError.codeOf(e) == SigError.NOT_LINKED) {
                    events.trySend(SigEvent.LoggedOut("notLinked"))
                    throw ActionNeededException("Signal is not linked. Link it again.", SIGNAL_PACKAGE)
                }
                throw e
            }
        }

    private fun messageKindOf(kind: AttachmentKind) =
        when (kind) {
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.VOICE, AttachmentKind.AUDIO -> MessageKind.VOICE
            AttachmentKind.STICKER -> MessageKind.STICKER
            else -> MessageKind.FILE
        }

    private fun sendFailure(e: Exception) =
        when (SigError.codeOf(e)) {
            SigError.NOT_CONNECTED -> "Not connected to Signal"
            SigError.REJECTED -> "Signal refused to send it"
            else -> "Could not send: ${e.message.orEmpty().substringAfter(": ")}"
        }

    private companion object {
        const val TAG = "PingMeSignal"
        const val SIGNAL_PACKAGE = "package:org.thoughtcrime.securesms"
        val REFERRING = setOf("reaction", "revoke", "edit", "typing")
    }
}

/**
 * A phone number as the address book holds it ("(919) 555-0123", "+1 919-555-0123") in the
 * +E.164 form Signal's directory wants. A number without a country code takes the account's
 * own; null for anything that is not a phone number.
 */
internal fun normalizeNumber(
    raw: String,
    ownPhone: String,
): String? {
    val digits = raw.filter { it.isDigit() }
    if (digits.length < MIN_DIGITS || digits.length > MAX_DIGITS) return null
    if (raw.trimStart().startsWith("+")) return "+$digits"
    val ownDigits = ownPhone.filter { it.isDigit() }
    val country = ownDigits.dropLast(NATIONAL_DIGITS).ifEmpty { "1" }
    return when {
        digits.length == NATIONAL_DIGITS -> "+$country$digits"
        digits.startsWith(country) && digits.length == country.length + NATIONAL_DIGITS -> "+$digits"
        else -> "+$digits"
    }
}

private const val MIN_DIGITS = 7
private const val MAX_DIGITS = 15
private const val NATIONAL_DIGITS = 10
