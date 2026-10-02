// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

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
import org.pingme.connectors.gmessages.bridge.GmBridge
import org.pingme.connectors.gmessages.bridge.GmCursor
import org.pingme.connectors.gmessages.bridge.GmError
import org.pingme.connectors.gmessages.bridge.GmEvent
import org.pingme.connectors.gmessages.bridge.GmMedia
import org.pingme.connectors.gmessages.bridge.GmMessage
import org.pingme.connectors.gmessages.bridge.GmSendRequest
import org.pingme.connectors.gmessages.bridge.GmSession
import org.pingme.connectors.gmessages.bridge.GoBridge
import org.pingme.connectors.gmessages.bridge.gmJson
import org.pingme.core.connector.ActionNeededException
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.connector.attachment
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.connector.remoteId
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.Transport
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One account's live pairing (BUILD_PLAN.md P3.2): the bridge session, the event loop
 * that turns the phone's events into [ConnectorEvent]s, and every request on it.
 *
 * Sending is asynchronous on Google Messages: the phone accepts the request, then echoes
 * the message back with the same tmpId. [send] waits for that echo so the pending bubble
 * becomes the real message; if the echo is slow, a provisional copy stands in and is
 * swapped for the real one when it arrives.
 */
@Suppress("TooManyFunctions") // One function per thing the connector can ask of the phone.
internal class GmessagesSession(
    val accountId: AccountId,
    bridge: GmBridge,
    authJson: String,
    private val credentialRef: String,
    private val credentials: CredentialStore,
    private val clock: Clock,
) {
    val go = GoBridge(accountId)

    /** The phone's events, plus connector events the session makes itself (stand-in removals). */
    private val events = Channel<Any>(Channel.UNLIMITED)
    private val session: GmSession =
        bridge.newSession(authJson) { json ->
            try {
                events.trySend(go.parse(json))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Unreadable event from the bridge", e)
            }
        }

    /** Sends waiting for the phone's copy, by tmpId. */
    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val myReactions = ConcurrentHashMap<String, String>()
    private val connected = AtomicBoolean(false)

    @Volatile private var chats: List<ChatSnapshot>? = null

    /**
     * One send: what went out, so the phone's copy can be matched by its tmpId, or, when the
     * phone drops that, by text and time. [waiter] is set while [send] waits; [standIn] once
     * a stand-in message has been shown instead.
     */
    private class Outgoing(
        val conversation: String,
        val text: String,
        val sentAt: Instant,
        @Volatile var waiter: CompletableDeferred<MessageSnapshot>?,
        @Volatile var standIn: MessageId? = null,
    )

    /** Connects, then streams events until [close] or a failure. */
    fun flow(): Flow<ConnectorEvent> =
        channelFlow {
            try {
                request { session.connect() }
                for (event in events) {
                    when (event) {
                        is GmEvent -> handle(event)
                        is ConnectorEvent -> send(event)
                    }
                }
            } finally {
                session.disconnect()
                outgoing.values.forEach { it.waiter?.cancel() }
            }
        }

    fun close() {
        events.close()
    }

    private suspend fun ProducerScope<ConnectorEvent>.handle(event: GmEvent) {
        endOfSession(event)?.let { throw it }
        when (event) {
            is GmEvent.Ready -> {
                val first = !connected.getAndSet(true)
                if (first || event.resync) listChats().forEach { send(ConnectorEvent.ChatUpdated(accountId, it)) }
                if (first) send(ConnectorEvent.State(accountId, ConnectionState.Connected))
            }

            is GmEvent.AuthUpdated -> {
                credentials.save(credentialRef, event.auth.toByteArray())
            }

            is GmEvent.Message -> {
                echoOrForward(event)
            }

            else -> {
                dataEvents(event).forEach { send(it) }
            }
        }
    }

    /** Chats, messages, typing, and settings: what GoBridge turns into connector events. */
    private suspend fun dataEvents(event: GmEvent): List<ConnectorEvent> =
        when (event) {
            is GmEvent.Typing -> {
                typingEvents(event)
            }

            is GmEvent.Settings -> {
                if (!event.settings.rcsEnabled) Log.i(TAG, "RCS is off in Google Messages; texts go as SMS")
                go.translate(event)
            }

            else -> {
                go.translate(event)
            }
        }

    /** Typing in a chat not listed yet (it can arrive before the list) fetches that chat first. */
    private suspend fun typingEvents(event: GmEvent.Typing): List<ConnectorEvent> {
        if (go.knows(event.conversationId)) return go.translate(event)
        val chat =
            try {
                go.chat(go.conversation(request { session.getConversation(event.conversationId) }))
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Typing in an unknown chat ${event.conversationId}", e)
                return emptyList()
            }
        return listOf(ConnectorEvent.ChatUpdated(accountId, chat)) + go.translate(event)
    }

    /** The events that end the session: unpaired needs the user; the rest get a reconnect. */
    private fun endOfSession(event: GmEvent): Exception? =
        when (event) {
            is GmEvent.LoggedOut -> loggedOut("Google Messages has unpaired PingMe (${event.reason}). Pair again.")
            is GmEvent.Fatal -> IOException("Google Messages connection failed: ${event.error}")
            is GmEvent.Reconnect -> IOException("Google Messages connection stalled; reconnecting")
            else -> null
        }

    /** A message echoing one of our sends completes the waiting send instead of arriving as new. */
    private suspend fun ProducerScope<ConnectorEvent>.echoOrForward(event: GmEvent.Message) {
        val msg = event.message
        val translated = go.translate(event)
        val match = matchOutgoing(msg)
        Log.i(
            TAG,
            "message id=${msg.id} conv=${msg.conversationId} tmpId=${msg.tmpId} status=${msg.statusName} " +
                "fromMe=${msg.fromMe} old=${event.isOld} chars=${msg.text.length} media=${msg.media.size} " +
                "reactions=${msg.reactions.size} match=${match?.let {
                    if (it.waiter != null) "waiting" else "standIn"
                } ?: "none"}",
        )
        val snapshot = translated.firstOrNull { it is ConnectorEvent.NewMessage || it is ConnectorEvent.MessageUpdated }
        val waiter = match?.waiter
        val standIn = match?.standIn
        when {
            match != null && snapshot != null && waiter != null -> {
                waiter.complete(snapshotOf(snapshot))
                translated.filterIsInstance<ConnectorEvent.ReactionChanged>().forEach { send(it) }
            }

            match != null && snapshot != null && standIn != null -> {
                send(ConnectorEvent.MessageRemoved(accountId, accountId.chat(msg.conversationId), standIn))
                send(ConnectorEvent.NewMessage(accountId, snapshotOf(snapshot)))
            }

            else -> {
                translated.forEach { send(it) }
            }
        }
    }

    /**
     * The send a message from the phone belongs to: by its tmpId, or, when the phone did
     * not keep that, the oldest recent send of the same text in the same chat.
     */
    private fun matchOutgoing(msg: GmMessage): Outgoing? {
        if (!msg.fromMe) return null
        val byTag = msg.tmpId.takeIf { it.isNotEmpty() }?.let { outgoing.remove(it) }
        val now = clock.now()
        val byText = {
            outgoing.entries
                .filter { (_, o) ->
                    o.conversation == msg.conversationId && o.text == msg.text && now - o.sentAt < MATCH_WINDOW
                }.minByOrNull { (_, o) -> o.sentAt }
                ?.key
                ?.let { outgoing.remove(it) }
        }
        return byTag ?: byText()
    }

    private fun snapshotOf(event: ConnectorEvent): MessageSnapshot =
        when (event) {
            is ConnectorEvent.NewMessage -> event.message
            is ConnectorEvent.MessageUpdated -> event.message
            else -> error("not a message event")
        }

    /** The inbox's chats, freshly listed. */
    suspend fun listChats(): List<ChatSnapshot> {
        val found = ArrayList<ChatSnapshot>()
        var cursor: String? = ""
        var pages = 0
        while (cursor != null && pages < CHAT_PAGES) {
            val from: String = cursor
            val page = go.conversationPage(request { session.listConversations("inbox", CHAT_PAGE, from) })
            page.conversations.filterNot(go::isGone).mapTo(found, go::chat)
            cursor = page.cursor?.let { gmJson.encodeToString(GmCursor.serializer(), it) }
            pages++
        }
        return found.also { chats = it }
    }

    /** The chats from the last listing, or a fresh one. */
    suspend fun syncChats(): List<ChatSnapshot> = chats ?: listChats()

    /** Up to [limit] messages older than [before] (the newest when null), newest first. */
    suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> {
        val conversation = chatId.remoteId
        val anchor = before?.remoteId
        var cursor = anchor?.let { go.cursorBefore(it) ?: findCursor(conversation, it, limit) }
        if (anchor != null && cursor == null) return emptyList()
        val page = fetch(conversation, limit, cursor)
        page.messages.forEach { retireStandIn(it) }
        return page.messages
            .filter { it.id != anchor && !it.hide }
            .filter { cursor == null || it.timestamp / MICROS_PER_MILLI < cursor.lastItemTimestamp }
            .map(go::message)
    }

    /** A sent message found in history retires its stand-in, as an echo would have. */
    private fun retireStandIn(msg: GmMessage) {
        val match = matchOutgoing(msg) ?: return
        match.waiter?.complete(go.message(msg))
        match.standIn?.let {
            events.trySend(
                ConnectorEvent.MessageRemoved(accountId, accountId.chat(msg.conversationId), it),
            )
        }
    }

    /** After a restart nothing is remembered: page from the newest until [anchor] shows up. */
    private suspend fun findCursor(
        conversation: String,
        anchor: String,
        limit: Int,
    ): GmCursor? {
        var cursor: GmCursor? = null
        var found: GmCursor? = null
        var pages = 0
        while (found == null && pages < SEARCH_PAGES) {
            val page = fetch(conversation, limit, cursor)
            found =
                page.messages.firstOrNull { it.id == anchor }?.let { GmCursor(it.id, it.timestamp / MICROS_PER_MILLI) }
            cursor = page.cursor ?: break
            pages++
        }
        return found
    }

    private suspend fun fetch(
        conversation: String,
        limit: Int,
        cursor: GmCursor?,
    ) = go.messagePage(
        request {
            session.fetchMessages(
                conversation,
                limit,
                cursor?.let { gmJson.encodeToString(GmCursor.serializer(), it) }.orEmpty(),
            )
        },
    )

    suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
        progress: (Float) -> Unit,
    ): SendResult {
        // draft.forceSms is ignored: Google Messages gives a paired device no "send this one as
        // SMS"; the phone picks RCS or SMS itself (owner, 2026-10-01).
        val conversation = chatId.remoteId
        val tmpId = tmpIdFor(draft.clientId)
        val media =
            draft.attachments.mapIndexed { i, file ->
                upload(file).also { progress((i + 1).toFloat() / draft.attachments.size) }
            }
        val waiter = CompletableDeferred<MessageSnapshot>()
        val record = Outgoing(conversation, draft.body.orEmpty(), clock.now(), waiter)
        outgoing[tmpId] = record
        Log.i(TAG, "send tmpId=$tmpId conv=$conversation chars=${draft.body.orEmpty().length} media=${media.size}")
        val refused =
            try {
                val body =
                    GmSendRequest(conversation, tmpId, draft.body.orEmpty(), draft.replyTo?.remoteId.orEmpty(), media)
                request { session.sendMessage(gmJson.encodeToString(GmSendRequest.serializer(), body)) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                outgoing.remove(tmpId)
                Log.w(TAG, "send tmpId=$tmpId refused", e)
                SendResult.Failed(sendFailure(e), retryable = GmError.codeOf(e) != GmError.REJECTED)
            }
        // Waited on a real-time dispatcher: the echo comes from the bridge's own thread, and a
        // caller on a test clock would otherwise skip the wait and get the stand-in.
        val echoed =
            if (refused ==
                null
            ) {
                withContext(Dispatchers.Default) { withTimeoutOrNull(ECHO_TIMEOUT) { waiter.await() } }
            } else {
                null
            }
        record.waiter = null
        if (refused == null &&
            echoed == null
        ) {
            Log.i(TAG, "send tmpId=$tmpId: no echo in $ECHO_TIMEOUT, showing a stand-in")
        }
        return refused ?: SendResult.Sent(echoed ?: standIn(chatId, draft, media, tmpId, record))
    }

    /**
     * The phone keeps a send's tmpId only when it looks like one of its own (a UUID, as
     * Google Messages generates). PingMe's pending ids are "pending-<uuid>", so the UUID
     * inside is used, or a fresh one.
     */
    private fun tmpIdFor(clientId: MessageId): String {
        val raw = clientId.remoteId.removePrefix("pending-")
        return runCatching { UUID.fromString(raw).toString() }.getOrElse { UUID.randomUUID().toString() }
    }

    private suspend fun upload(file: OutgoingAttachment): GmMedia {
        val json =
            request { session.uploadMedia(file.localPath, file.fileName ?: File(file.localPath).name, file.mimeType) }
        return gmJson.decodeFromString(GmMedia.serializer(), json)
    }

    /** The message as sent, shown until the phone echoes the real one. */
    private fun standIn(
        chatId: ChatId,
        draft: OutgoingMessage,
        media: List<GmMedia>,
        tmpId: String,
        record: Outgoing,
    ): MessageSnapshot {
        val id = accountId.message("$STAND_IN$tmpId")
        record.standIn = id
        val now = clock.now()
        val attachments =
            draft.attachments.mapIndexed { i, file ->
                val ref = GoBridge.MediaRef(tmpId, "$i", media[i].mediaId, media[i].key, "", "")
                Attachment(
                    id = accountId.attachment("$tmpId/$i"),
                    kind = file.kind,
                    mimeType = file.mimeType,
                    fileName = file.fileName,
                    sizeBytes = File(file.localPath).length(),
                    localPath = file.localPath,
                    remoteRef = ref.encode(),
                    durationMs = file.durationMs,
                    width = null,
                    height = null,
                    isEphemeral = false,
                    savedAt = null,
                )
            }
        val message =
            Message(
                id = id,
                chatId = chatId,
                senderId = accountId.person(go.outgoingId(chatId.remoteId) ?: "me"),
                sentAt = now,
                receivedAt = now,
                body = draft.body?.ifBlank { null },
                kind = attachments.firstOrNull()?.let { kindOf(it.kind) } ?: MessageKind.TEXT,
                attachments = attachments,
                replyTo = draft.replyTo,
                quote = draft.quote,
                editedAt = null,
                deletedForEveryone = false,
                status = MessageStatus.Sent,
                reactions = emptyList(),
                transport = if (go.isRcs(chatId.remoteId) == false) Transport.SMS else Transport.RCS,
                networkRemoteId = tmpId,
                linkPreview = null,
                isOutgoing = true,
            )
        return MessageSnapshot(message, null)
    }

    suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) {
        val remote = messageId.remoteId
        if (!isPhoneId(remote)) throw UnsupportedCapabilityException("Wait for the message to finish sending")
        val conversation = go.conversationOf(remote).orEmpty()
        val mine = myReactions[remote] ?: go.myReaction(remote)
        if (remove) {
            val target = emoji ?: mine ?: return
            request { session.sendReaction(conversation, remote, target, "remove") }
            myReactions.remove(remote)
        } else {
            val chosen = requireNotNull(emoji) { "an emoji to add" }
            request { session.sendReaction(conversation, remote, chosen, if (mine != null) "switch" else "add") }
            myReactions[remote] = chosen
        }
    }

    suspend fun delete(messageId: MessageId) {
        val remote = messageId.remoteId
        if (!isPhoneId(remote)) return
        request { session.deleteMessage(remote) }
    }

    suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) {
        // Only ids the phone gave out; its own pending and stand-in ids mean nothing to it.
        if (!isPhoneId(upTo.remoteId)) return
        request { session.markRead(chatId.remoteId, upTo.remoteId) }
    }

    suspend fun typing(chatId: ChatId) = request { session.setTyping(chatId.remoteId) }

    /** Finds or starts a chat; the new chat is announced through the event stream too. */
    suspend fun openChat(
        numbers: List<String>,
        groupName: String,
    ): ChatId {
        val json =
            request {
                session.getOrCreateConversation(
                    gmJson.encodeToString(ListSerializer(String.serializer()), numbers),
                    groupName,
                )
            }
        val conversation = go.conversation(json)
        events.trySend(GmEvent.Conversation(conversation))
        return accountId.chat(conversation.id)
    }

    /** Downloads an attachment's file (full size, or the thumbnail while the phone uploads the rest). */
    suspend fun download(
        ref: GoBridge.MediaRef,
        target: File,
    ): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        if (ref.mediaId.isNotEmpty()) {
            request { session.downloadMedia(ref.mediaId, ref.key, target.absolutePath) }
        } else {
            request { session.downloadMedia(ref.thumbnailMediaId, ref.thumbnailKey, target.absolutePath) }
            request { session.requestFullSizeMedia(ref.messageId, ref.partId) }
        }
        return target
    }

    /** Runs a bridge call off the main thread; a logged-out answer also ends the session. */
    private suspend fun <T> request(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (GmError.codeOf(e) == GmError.LOGGED_OUT) {
                    events.trySend(GmEvent.LoggedOut("rejected", e.message.orEmpty()))
                    throw loggedOut("Google Messages no longer accepts this pairing. Pair again.")
                }
                throw e
            }
        }

    private fun loggedOut(reason: String) = ActionNeededException(reason, GOOGLE_MESSAGES_PACKAGE)

    /** Google Messages ids are plain numbers; anything else is one of PingMe's own placeholders. */
    private fun isPhoneId(remote: String) = remote.isNotEmpty() && remote.all { it.isDigit() }

    private fun kindOf(kind: AttachmentKind) =
        when (kind) {
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.VOICE -> MessageKind.VOICE
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.STICKER -> MessageKind.STICKER
            AttachmentKind.CONTACT -> MessageKind.CONTACT
            AttachmentKind.LOCATION -> MessageKind.LOCATION
            AttachmentKind.AUDIO, AttachmentKind.FILE -> MessageKind.FILE
        }

    private fun sendFailure(e: Exception) =
        when (GmError.codeOf(e)) {
            GmError.PHONE_NOT_RESPONDING -> "The phone did not answer. Check Google Messages is open and online."
            GmError.NOT_CONNECTED -> "Not connected to Google Messages"
            GmError.REJECTED -> "Google Messages refused to send it"
            else -> "Could not send: ${e.message.orEmpty().substringAfter(": ")}"
        }

    private companion object {
        const val TAG = "PingMeGmessages"
        const val CHAT_PAGE = 50
        const val CHAT_PAGES = 4
        const val SEARCH_PAGES = 20
        const val MICROS_PER_MILLI = 1000
        const val STAND_IN = "tmp/"
        val ECHO_TIMEOUT = 20.seconds
        val MATCH_WINDOW = 3.minutes
    }
}
