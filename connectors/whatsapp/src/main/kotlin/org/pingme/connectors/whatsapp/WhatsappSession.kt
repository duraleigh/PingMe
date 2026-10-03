// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.pingme.connectors.whatsapp.bridge.WaBridge
import org.pingme.connectors.whatsapp.bridge.WaContact
import org.pingme.connectors.whatsapp.bridge.WaError
import org.pingme.connectors.whatsapp.bridge.WaEvent
import org.pingme.connectors.whatsapp.bridge.WaLocation
import org.pingme.connectors.whatsapp.bridge.WaMessage
import org.pingme.connectors.whatsapp.bridge.WaReply
import org.pingme.connectors.whatsapp.bridge.WaSession
import org.pingme.connectors.whatsapp.bridge.WaTranslate
import org.pingme.connectors.whatsapp.bridge.waJson
import org.pingme.core.connector.ActionNeededException
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.connector.remoteId
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageId
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * One linked WhatsApp account's live connection (BUILD_PLAN.md Phase 6, network 1): the
 * bridge session, the event loop that turns WhatsApp's events into [ConnectorEvent]s,
 * and every request on it.
 */
@Suppress("TooManyFunctions") // One function per thing the connector can ask of WhatsApp.
internal class WhatsappSession(
    val accountId: AccountId,
    bridge: WaBridge,
    dbPath: String,
) {
    private val events = Channel<Any>(Channel.UNLIMITED)
    private val session: WaSession =
        bridge.newSession(dbPath) { json ->
            try {
                events.trySend(go.parse(json))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Unreadable event from the bridge", e)
            }
        }
    val go = WaTranslate(accountId, contactName = { session.contactName(it) }, phoneOf = { session.phoneOf(it) })

    /** History pages asked for by the history worker, by chat. */
    private val historyWaiters = ConcurrentHashMap<String, CompletableDeferred<WaEvent.History>>()
    private val connected = AtomicBoolean(false)

    /** Connects, then streams events until [close] or a failure. */
    fun flow(): Flow<ConnectorEvent> =
        channelFlow {
            try {
                if (!session.isLoggedIn()) throw ActionNeededException("WhatsApp is not linked. Link it again.", null)
                go.ownId = session.ownId()
                go.ownLid = session.ownLid()
                go.ownPhone = session.ownPhone()
                request { session.connect() }
                for (event in events) {
                    when (event) {
                        is WaEvent -> handle(event)
                        is ConnectorEvent -> send(event)
                    }
                }
            } finally {
                session.disconnect()
                historyWaiters.values.forEach { it.cancel() }
            }
        }

    fun close() {
        events.close()
    }

    private suspend fun ProducerScope<ConnectorEvent>.handle(event: WaEvent) {
        endOfSession(event)?.let { throw it }
        when (event) {
            is WaEvent.Connected -> {
                onConnected(event)
            }

            is WaEvent.History -> {
                // A page the history worker asked for goes back to it; everything else flows to the store.
                val waiter = if (event.syncType == ON_DEMAND) historyWaiters.remove(event.chat.id) else null
                if (waiter != null) waiter.complete(event) else go.translate(event).forEach { send(it) }
            }

            is WaEvent.Disconnected -> {
                Log.i(TAG, "WhatsApp connection dropped; the bridge reconnects by itself")
            }

            is WaEvent.Contacts -> {
                // Names have arrived from the phone: every chat reads again with them.
                learnNames()
                go.allChats().forEach { send(ConnectorEvent.ChatUpdated(accountId, it)) }
            }

            is WaEvent.KeepAliveTimeout, is WaEvent.ConnectFailure, is WaEvent.Undecryptable -> {
                Log.w(TAG, "WhatsApp: $event")
            }

            else -> {
                go.translate(event).forEach { send(it) }
            }
        }
    }

    private suspend fun ProducerScope<ConnectorEvent>.onConnected(event: WaEvent.Connected) {
        go.ownId = event.id.ifEmpty { go.ownId }
        go.ownLid = event.lid.ifEmpty { go.ownLid }
        go.ownPhone = event.phone.ifEmpty { go.ownPhone }
        learnNames()
        listChats().forEach { send(ConnectorEvent.ChatUpdated(accountId, it)) }
        spaces().forEach { send(ConnectorEvent.SpaceUpdated(accountId, it)) }
        if (!connected.getAndSet(true)) send(ConnectorEvent.State(accountId, ConnectionState.Connected))
    }

    /** The events that end the session: unlinked needs the user; the rest get a reconnect. */
    private fun endOfSession(event: WaEvent): Exception? =
        when (event) {
            is WaEvent.LoggedOut -> {
                loggedOut("WhatsApp has unlinked PingMe (${event.reason}). Link it again.")
            }

            is WaEvent.ClientOutdated -> {
                loggedOut("WhatsApp no longer accepts this version of PingMe. Update PingMe.")
            }

            is WaEvent.TemporaryBan -> {
                val hours = event.expireSeconds / SECONDS_PER_HOUR
                loggedOut("WhatsApp has paused this link for about $hours hours (${event.reason}).")
            }

            is WaEvent.StreamReplaced -> {
                IOException("Another device took over this WhatsApp link; reconnecting")
            }

            else -> {
                null
            }
        }

    private fun loggedOut(reason: String) = ActionNeededException(reason, WHATSAPP_PACKAGE)

    private suspend fun ProducerScope<ConnectorEvent>.learnNames() {
        try {
            val contacts = go.participants(request { session.contacts() })
            go.learnNames(contacts)
            send(ConnectorEvent.PeopleUpdated(accountId, go.people(contacts)))
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Could not read WhatsApp's contact names", e)
        }
    }

    /** Every joined group as a chat; communities become spaces instead. */
    suspend fun listChats(): List<ChatSnapshot> {
        val groups = go.chatList(request { session.listGroups() })
        groups.forEach { go.chat(it) }
        return groups.filter { !it.isCommunity }.map { go.chat(it) }
    }

    private fun spaces() =
        go
            .chatList("[]")
            .let { emptyList<org.pingme.core.model.Space>() }
            .ifEmpty { communityIds.mapNotNull { go.spaceOf(it) } }

    private val communityIds: List<String>
        get() = lastCommunities

    @Volatile private var lastCommunities: List<String> = emptyList()

    suspend fun syncChats(): List<ChatSnapshot> {
        val groups = go.chatList(request { session.listGroups() })
        lastCommunities = groups.filter { it.isCommunity }.map { it.id }
        groups.forEach { go.chat(it) }
        return groups.filter { !it.isCommunity }.map { go.chat(it) }
    }

    /**
     * Older messages: first from what this session has already seen of the chat (the
     * history sync and live messages), then by asking the phone for a page before [before]
     * and waiting for the answer. A message this session has not seen (after a restart)
     * ends the history here.
     */
    suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> {
        val chat = chatId.remoteId
        val anchorId = before?.remoteId?.substringAfterLast('/')
        var remembered = go.recentPage(chat, anchorId, limit)
        // Right after connecting, the history sync is still landing: a first page waits for it a little.
        var waited = 0
        while (before == null && remembered.isNullOrEmpty() && waited < FIRST_PAGE_WAIT_MS) {
            kotlinx.coroutines.delay(FIRST_PAGE_STEP_MS.toLong())
            waited += FIRST_PAGE_STEP_MS
            remembered = go.recentPage(chat, null, limit)
        }
        val seen = before?.let { go.seen(it) }
        return when {
            remembered == null || remembered.isNotEmpty() || before == null || seen == null -> remembered.orEmpty()
            else -> askPhone(chat, before, seen, limit)
        }
    }

    private suspend fun askPhone(
        chat: String,
        anchor: MessageId,
        seen: WaTranslate.Seen,
        limit: Int,
    ): List<MessageSnapshot> {
        val waiter = CompletableDeferred<WaEvent.History>()
        historyWaiters[chat] = waiter
        try {
            val id = anchor.remoteId.substringAfterLast('/')
            request { session.requestHistory(chat, id, seen.timestamp, seen.fromMe, limit) }
            val page = withContext(Dispatchers.Default) { withTimeoutOrNull(HISTORY_WAIT) { waiter.await() } }
            return page?.let { go.historyPage(it) }.orEmpty().filter { it.message.id != anchor }
        } finally {
            historyWaiters.remove(chat, waiter)
        }
    }

    suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
        progress: (Float) -> Unit,
    ): SendResult {
        val chat = chatId.remoteId
        val reply = replyJson(draft)
        return try {
            val files = draft.attachments
            if (files.isEmpty()) {
                val json = request { session.sendText(chat, draft.body.orEmpty(), reply) }
                SendResult.Sent(go.message(go.messageJson(json)))
            } else {
                // WhatsApp carries one file per message: the text rides as the first one's caption,
                // and any further files go as messages of their own, announced through the stream.
                var first: MessageSnapshot? = null
                files.forEachIndexed { i, file ->
                    val caption = if (i == 0) draft.body.orEmpty() else ""
                    val json =
                        request {
                            session.sendMedia(
                                chat,
                                file.localPath,
                                file.mimeType,
                                kindOf(file),
                                file.fileName.orEmpty(),
                                caption,
                                (file.durationMs ?: 0) / MILLIS,
                                0,
                                0,
                                if (i == 0) reply else "",
                            )
                        }
                    val sent = go.message(go.messageJson(json)).keepingFile(file)
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
            SendResult.Failed(sendFailure(e), retryable = WaError.codeOf(e) != WaError.REJECTED)
        }
    }

    /** The phone's copy of a sent file names it but has no local file; PingMe just uploaded this one. */
    private fun MessageSnapshot.keepingFile(file: OutgoingAttachment): MessageSnapshot {
        val attachments =
            message.attachments.map {
                it.copy(
                    localPath = file.localPath,
                    durationMs =
                        it.durationMs ?: file.durationMs,
                )
            }
        return copy(message = message.copy(attachments = attachments))
    }

    private fun replyJson(draft: OutgoingMessage): String {
        val target = draft.replyTo ?: return ""
        val seen = go.seen(target)
        val reply =
            WaReply(
                id = target.remoteId.substringAfterLast('/'),
                sender = seen?.sender.orEmpty(),
                fromMe = seen?.fromMe ?: false,
                text = draft.quote?.text.orEmpty(),
            )
        return waJson.encodeToString(WaReply.serializer(), reply)
    }

    suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) {
        val seen = go.seen(messageId) ?: throw UnsupportedCapabilityException("Wait for the message to finish sending")
        val id = messageId.remoteId.substringAfterLast('/')
        request {
            session.sendReaction(
                seen.chat,
                id,
                seen.sender,
                seen.fromMe,
                if (remove) "" else requireNotNull(emoji),
            )
        }
    }

    suspend fun revoke(messageId: MessageId) {
        val seen =
            go.seen(messageId) ?: throw UnsupportedCapabilityException("This message cannot be deleted for everyone")
        request { session.revoke(seen.chat, messageId.remoteId.substringAfterLast('/'), seen.sender, seen.fromMe) }
    }

    suspend fun edit(
        messageId: MessageId,
        text: String,
    ) {
        val seen = go.seen(messageId) ?: throw UnsupportedCapabilityException("This message cannot be edited")
        request { session.edit(seen.chat, messageId.remoteId.substringAfterLast('/'), text) }
    }

    suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) {
        val seen = go.seen(upTo) ?: return
        if (seen.fromMe) return
        val ids =
            waJson.encodeToString(
                ListSerializer(String.serializer()),
                listOf(upTo.remoteId.substringAfterLast('/')),
            )
        request { session.markRead(chatId.remoteId, seen.sender, ids, seen.timestamp) }
    }

    suspend fun typing(
        chatId: ChatId,
        typing: Boolean,
    ) = request { session.setTyping(chatId.remoteId, typing) }

    /** A one-to-one chat with an international number, if it is on WhatsApp. */
    suspend fun startChat(number: String): ChatId {
        val jid = request { session.checkNumber(number) }
        if (jid.isEmpty()) throw UnsupportedCapabilityException("That number is not on WhatsApp")
        val chat = go.directChat(jid)
        events.trySend(ConnectorEvent.ChatUpdated(accountId, chat))
        return chat.id
    }

    suspend fun createGroup(
        title: String,
        numbers: List<String>,
    ): ChatId {
        val members = numbers.map { request { session.checkNumber(it) } }.filter { it.isNotEmpty() }
        if (members.isEmpty()) throw UnsupportedCapabilityException("None of those numbers is on WhatsApp")
        val json =
            request { session.createGroup(title, waJson.encodeToString(ListSerializer(String.serializer()), members)) }
        val chat = go.chat(go.chatJson(json))
        events.trySend(ConnectorEvent.ChatUpdated(accountId, chat))
        return chat.id
    }

    suspend fun block(chatId: ChatId) = request { session.block(chatId.remoteId) }

    /** Downloads an attachment's file; places and contacts are written from what the message carried. */
    suspend fun download(
        attachment: Attachment,
        target: File,
    ): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        val ref = requireNotNull(attachment.remoteRef) { "nothing to fetch" }
        when (attachment.kind) {
            AttachmentKind.LOCATION -> {
                val place = waJson.decodeFromString(WaLocation.serializer(), ref)
                target.writeText("""{"type":"Point","coordinates":[${place.longitude},${place.latitude}]}""")
            }

            AttachmentKind.CONTACT -> {
                target.writeText(waJson.decodeFromString(WaContact.serializer(), ref).vcard)
            }

            else -> {
                val media = go.mediaRef(attachment.id.remoteId, ref)
                request { session.download(media, target.absolutePath) }
            }
        }
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
                if (WaError.codeOf(e) == WaError.NOT_LOGGED_IN) {
                    events.trySend(WaEvent.LoggedOut("notLoggedIn"))
                    throw loggedOut("WhatsApp is not linked. Link it again.")
                }
                throw e
            }
        }

    private fun kindOf(file: OutgoingAttachment) =
        when (file.kind) {
            AttachmentKind.IMAGE -> "image"
            AttachmentKind.GIF -> "gif"
            AttachmentKind.VIDEO -> "video"
            AttachmentKind.VOICE -> "voice"
            AttachmentKind.AUDIO -> "audio"
            AttachmentKind.STICKER -> "sticker"
            else -> "document"
        }

    private fun sendFailure(e: Exception) =
        when (WaError.codeOf(e)) {
            WaError.NOT_CONNECTED -> "Not connected to WhatsApp"
            WaError.REJECTED -> "WhatsApp refused to send it"
            else -> "Could not send: ${e.message.orEmpty().substringAfter(": ")}"
        }

    private companion object {
        const val TAG = "PingMeWhatsapp"
        const val ON_DEMAND = "ON_DEMAND"
        const val MILLIS = 1000L
        const val SECONDS_PER_HOUR = 3600L
        const val WHATSAPP_PACKAGE = "package:com.whatsapp"
        val HISTORY_WAIT = 30.seconds
        const val FIRST_PAGE_WAIT_MS = 10_000
        const val FIRST_PAGE_STEP_MS = 100
    }
}
