// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.pingme.connectors.messenger.bridge.FbBridge
import org.pingme.connectors.messenger.bridge.FbError
import org.pingme.connectors.messenger.bridge.FbEvent
import org.pingme.connectors.messenger.bridge.FbMedia
import org.pingme.connectors.messenger.bridge.FbSession
import org.pingme.connectors.messenger.bridge.FbTranslate
import org.pingme.connectors.messenger.bridge.fbJson
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
 * One signed-in Facebook account's live connection (BUILD_PLAN.md Phase 6, network 6):
 * the bridge session, the event loop that turns Messenger's events into
 * [ConnectorEvent]s, and every request on it. Refreshed cookies are saved as they come.
 */
@Suppress("TooManyFunctions") // One function per thing the connector can ask of Messenger.
internal class MessengerSession(
    val accountId: AccountId,
    bridge: FbBridge,
    cookiesJson: String,
    private val credentialRef: String,
    private val credentials: CredentialStore,
    storePath: String = "",
) {
    private val events = Channel<Any>(Channel.UNLIMITED)
    val go = FbTranslate(accountId)
    private val session: FbSession =
        bridge.newSession(cookiesJson, storePath) { json ->
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
    }

    /** Connects, then streams events until [close] or a failure. */
    fun flow(): Flow<ConnectorEvent> =
        channelFlow {
            try {
                request { session.connect() }
                go.ownId = session.ownId()
                for (event in events) {
                    when (event) {
                        is FbEvent -> handle(event)
                        is ConnectorEvent -> send(event)
                    }
                }
            } finally {
                session.disconnect()
            }
        }

    fun close() {
        events.close()
    }

    private suspend fun ProducerScope<ConnectorEvent>.handle(event: FbEvent) {
        when (event) {
            is FbEvent.Connected -> {
                go.ownId = event.id.ifEmpty { go.ownId }
                Log.i(TAG, "Messenger signed in as ${go.ownId}")
                if (event.cookies.isNotEmpty()) credentials.save(credentialRef, event.cookies.toByteArray())
            }

            is FbEvent.InboxLoaded -> {
                if (!connected.getAndSet(true)) send(ConnectorEvent.State(accountId, ConnectionState.Connected))
            }

            is FbEvent.LoggedOut -> {
                throw ActionNeededException(
                    "Messenger signed PingMe out (${event.error}). Sign in again.",
                    MESSENGER_PACKAGE,
                )
            }

            is FbEvent.Disconnected -> {
                if (event.permanent) throw IOException("Messenger's connection ended (${event.error}); reconnecting")
                Log.i(TAG, "Messenger connection dropped (${event.error}); the bridge reconnects by itself")
            }

            is FbEvent.Live -> {
                Unit
            }

            is FbEvent.E2ee -> {
                Log.i(TAG, "Messenger encrypted channel: ${event.state} ${event.error}".trimEnd())
            }

            else -> {
                go.translate(event).forEach { send(it) }
            }
        }
    }

    /** The inbox and the request queue: what the page carried plus a few pages of older chats. */
    suspend fun syncChats(): List<ChatSnapshot> {
        var pages = 0
        while (pages < MAX_PAGES) {
            val more =
                try {
                    request { session.moreThreads() }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Log.w(TAG, "Could not list older chats", e)
                    false
                }
            pages++
            if (!more) break
        }
        val chats = go.threadsJson(request { session.threads() }).map { go.chat(it) }
        Log.i(TAG, "Messenger listed ${chats.size} chats after $pages pages")
        return chats
    }

    /** Up to [limit] messages older than [before] (the newest when null), newest first. */
    suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> {
        val thread = chatId.remoteId
        val anchor = before?.remoteId?.substringAfter('/').orEmpty()
        val page = go.messagesJson(request { session.messages(thread, anchor) })
        if (anchor.isEmpty()) Log.i(TAG, "Messenger history for $thread: ${page.size} messages on the first page")
        return page
            .filter { it.kind != "system" && it.id != anchor }
            .sortedByDescending { it.timestamp }
            .take(limit)
            .map { go.message(it.copy(thread = it.thread.ifEmpty { thread })) }
    }

    suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
        progress: (Float) -> Unit,
    ): SendResult {
        val thread = chatId.remoteId
        val reply =
            draft.replyTo
                ?.remoteId
                ?.substringAfter('/')
                .orEmpty()
        return try {
            val files = draft.attachments
            if (files.isEmpty()) {
                val json = request { session.sendText(thread, draft.body.orEmpty(), reply) }
                SendResult.Sent(go.message(go.messageJson(json).copy(thread = thread)))
            } else {
                // Messenger carries one file per message; the text rides with the first.
                var first: MessageSnapshot? = null
                files.forEachIndexed { i, file ->
                    val text = if (i == 0) draft.body.orEmpty() else ""
                    val json =
                        request {
                            session.sendMedia(
                                thread,
                                file.localPath,
                                file.mimeType,
                                kindOf(file),
                                file.fileName.orEmpty(),
                                text,
                                if (i == 0) reply else "",
                            )
                        }
                    val sent = go.message(go.messageJson(json).copy(thread = thread)).keepingFile(file)
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
            Log.w(TAG, "send to $thread failed", e)
            SendResult.Failed(sendFailure(e), retryable = FbError.codeOf(e) != FbError.REJECTED)
        }
    }

    private fun MessageSnapshot.keepingFile(file: OutgoingAttachment): MessageSnapshot {
        val kept =
            Attachment(
                id = accountId.attachment("${message.chatId.remoteId}/${message.networkRemoteId}/0"),
                kind = file.kind,
                mimeType = file.mimeType,
                fileName = file.fileName,
                sizeBytes = File(file.localPath).length(),
                localPath = file.localPath,
                remoteRef = null,
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
        val thread = messageId.remoteId.substringBefore('/')
        request { session.sendReaction(thread, messageId.remoteId.substringAfter('/'), emoji.orEmpty(), remove) }
    }

    suspend fun unsend(messageId: MessageId) = request { session.unsend(messageId.remoteId.substringAfter('/')) }

    suspend fun edit(
        messageId: MessageId,
        text: String,
    ) = request { session.edit(messageId.remoteId.substringAfter('/'), text) }

    suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) {
        val at = go.seen(upTo)?.timestamp ?: System.currentTimeMillis()
        request { session.markRead(chatId.remoteId, at) }
    }

    suspend fun typing(
        chatId: ChatId,
        typing: Boolean,
    ) = request { session.setTyping(chatId.remoteId, typing) }

    suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ) = request { if (accept) session.acceptRequest(chatId.remoteId) else session.deleteThread(chatId.remoteId) }

    /** A new chat with a person, by user id or by searching a name (the first match). */
    suspend fun startConversation(handle: String): ChatId {
        val userId =
            if (handle.all { it.isDigit() }) {
                handle
            } else {
                go
                    .usersJson(request { session.searchUsers(handle) })
                    .firstOrNull()
                    ?.id
                    ?: throw UnsupportedCapabilityException("No one on Messenger matches \"$handle\"")
            }
        return go.chatId(request { session.startChat(userId) })
    }

    suspend fun download(
        attachment: Attachment,
        target: File,
    ): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        val media =
            fbJson.decodeFromString(
                FbMedia.serializer(),
                requireNotNull(attachment.remoteRef) { "nothing to fetch" },
            )
        request { session.download(media.url, media.mime.ifEmpty { attachment.mimeType }, target.absolutePath) }
        return target
    }

    /** Runs a bridge call off the main thread; a signed-out answer also ends the session. */
    private suspend fun <T> request(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (FbError.codeOf(e) == FbError.LOGGED_OUT) {
                    events.trySend(FbEvent.LoggedOut(e.message.orEmpty()))
                    throw ActionNeededException("Messenger signed PingMe out. Sign in again.", MESSENGER_PACKAGE)
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

    private fun kindOf(file: OutgoingAttachment) =
        when (file.kind) {
            AttachmentKind.IMAGE -> "image"
            AttachmentKind.VIDEO -> "video"
            AttachmentKind.GIF -> "gif"
            AttachmentKind.VOICE, AttachmentKind.AUDIO -> "voice"
            else -> "file"
        }

    private fun sendFailure(e: Exception) =
        when (FbError.codeOf(e)) {
            FbError.NOT_CONNECTED -> "Not connected to Messenger"
            FbError.REJECTED -> "Messenger refused to send it"
            else -> "Could not send: ${e.message.orEmpty().substringAfter(": ")}"
        }

    private companion object {
        const val TAG = "PingMeMessenger"
        const val MAX_PAGES = 4
        const val MESSENGER_PACKAGE = "package:com.facebook.orca"
    }
}
