// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gvoice

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.pingme.connectors.gvoice.bridge.GvBridge
import org.pingme.connectors.gvoice.bridge.GvError
import org.pingme.connectors.gvoice.bridge.GvEvent
import org.pingme.connectors.gvoice.bridge.GvMedia
import org.pingme.connectors.gvoice.bridge.GvSession
import org.pingme.connectors.gvoice.bridge.GvTranslate
import org.pingme.connectors.gvoice.bridge.gvJson
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One signed-in Google Voice account's live connection (BUILD_PLAN.md Phase 6, network
 * 4): the bridge session, the event loop that turns its events into [ConnectorEvent]s,
 * and every request on it. Refreshed cookies are saved as they come.
 */
@Suppress("TooManyFunctions") // One function per thing the connector can ask of Google Voice.
internal class GvoiceSession(
    val accountId: AccountId,
    bridge: GvBridge,
    cookiesJson: String,
    private val credentialRef: String,
    private val credentials: CredentialStore,
) {
    private val events = Channel<Any>(Channel.UNLIMITED)
    private val session: GvSession =
        bridge.newSession(cookiesJson) { json ->
            try {
                events.trySend(go.parse(json))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Unreadable event from the bridge", e)
            }
        }
    val go = GvTranslate(accountId) { phone -> runCatching { session.nameOf(phone) }.getOrDefault("") }
    private val connected = AtomicBoolean(false)

    /** The token for the page after the oldest message PingMe has of each thread. */
    private val pageTokens = ConcurrentHashMap<String, Pair<String, String>>()

    init {
        go.ownPhone = session.ownPhone()
    }

    /** Connects, then streams events until [close] or a failure. */
    fun flow(): Flow<ConnectorEvent> =
        channelFlow {
            try {
                request { session.connect() }
                go.ownPhone = session.ownPhone()
                for (event in events) {
                    when (event) {
                        is GvEvent -> handle(event)
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

    private suspend fun ProducerScope<ConnectorEvent>.handle(event: GvEvent) {
        when (event) {
            is GvEvent.Connected -> {
                go.ownPhone = event.phone.ifEmpty { go.ownPhone }
            }

            is GvEvent.Cookies -> {
                if (event.cookies.isNotEmpty()) credentials.save(credentialRef, event.cookies.toByteArray())
            }

            is GvEvent.Contacts -> {
                go.learnNames(event.people)
                send(ConnectorEvent.PeopleUpdated(accountId, go.people(event.people)))
            }

            is GvEvent.InboxLoaded -> {
                if (!connected.getAndSet(true)) send(ConnectorEvent.State(accountId, ConnectionState.Connected))
            }

            is GvEvent.LoggedOut -> {
                throw ActionNeededException(
                    "Google has signed PingMe out of Google Voice. Sign in again.",
                    GVOICE_PACKAGE,
                )
            }

            is GvEvent.ConnectError -> {
                if (event.fatal) throw IOException("Google Voice connection failed (${event.reason}); reconnecting")
                Log.w(TAG, "Google Voice connection trouble: ${event.reason}")
            }

            is GvEvent.Live -> {
                Unit
            }

            else -> {
                go.translate(event).forEach { send(it) }
            }
        }
    }

    suspend fun syncChats(): List<ChatSnapshot> = go.threadsJson(request { session.threads() }).map { go.chat(it) }

    /**
     * Up to [limit] messages older than [before] (the newest when null), newest first.
     * Google Voice pages by a token it hands out with each page; when the token for
     * [before] is not known (after a restart), the thread is read from the top and cut.
     */
    suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> {
        val thread = chatId.remoteId
        val anchor = before?.remoteId?.substringAfterLast('/').orEmpty()
        val known = pageTokens[thread]?.takeIf { it.first == anchor }?.second
        val page =
            if (anchor.isEmpty() || known != null) {
                go.threadJson(request { session.thread(thread, limit, known.orEmpty()) })
            } else {
                val whole = go.threadJson(request { session.thread(thread, limit * WIDE_PAGE, "") })
                val sorted = whole.messages.sortedByDescending { it.timestamp }
                val at = sorted.indexOfFirst { it.id == anchor }
                if (at < 0) return emptyList()
                whole.copy(messages = sorted.drop(at + 1).take(limit))
            }
        val messages = page.messages.filter { it.id != anchor }.sortedByDescending { it.timestamp }
        messages.lastOrNull()?.let { oldest ->
            if (page.paginationToken.isNotEmpty()) pageTokens[thread] = oldest.id to page.paginationToken
        }
        return messages.map { go.message(it.copy(thread = it.thread.ifEmpty { thread })) }
    }

    suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
        progress: (Float) -> Unit,
    ): SendResult {
        val thread = chatId.remoteId
        // No native replies on Google Voice: a quoted line leads the text (UI_DESIGN.md 5.2).
        val text =
            listOfNotNull(
                draft.quote
                    ?.text
                    ?.takeIf {
                        it.isNotBlank()
                    }?.let { "> ${it.lineSequence().first()}" },
                draft.body?.takeIf { it.isNotBlank() },
            ).joinToString("\n")
        return try {
            val files = draft.attachments
            if (files.isEmpty()) {
                SendResult.Sent(
                    go.message(go.messageJson(request { session.sendText(thread, text) }).copy(thread = thread)),
                )
            } else {
                var first: MessageSnapshot? = null
                files.forEachIndexed { i, file ->
                    if (file.kind != AttachmentKind.IMAGE && file.kind != AttachmentKind.GIF) {
                        throw UnsupportedCapabilityException("Google Voice sends pictures only")
                    }
                    val json =
                        request {
                            session.sendMedia(
                                thread,
                                file.localPath,
                                file.mimeType,
                                if (i ==
                                    0
                                ) {
                                    text
                                } else {
                                    ""
                                },
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
        } catch (e: UnsupportedCapabilityException) {
            SendResult.Failed(e.message ?: "Google Voice cannot send that", retryable = false)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "send to $thread failed", e)
            SendResult.Failed(sendFailure(e), retryable = GvError.codeOf(e) != GvError.REJECTED)
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
                durationMs = null,
                width = null,
                height = null,
                isEphemeral = false,
                savedAt = null,
            )
        val kind = if (file.kind == AttachmentKind.GIF) MessageKind.GIF else MessageKind.IMAGE
        return copy(message = message.copy(attachments = listOf(kept), kind = kind))
    }

    /** A reaction goes out as a text, the way Google Messages does on SMS (UI_DESIGN.md 5.4). */
    suspend fun reactAsText(
        messageId: MessageId,
        emoji: String,
        quoted: String,
    ) {
        val thread = messageId.remoteId.substringBeforeLast('/')
        val text = "Reacted $emoji to “$quoted”"
        val sent = go.message(go.messageJson(request { session.sendText(thread, text) }).copy(thread = thread))
        events.trySend(ConnectorEvent.NewMessage(accountId, sent))
    }

    suspend fun markRead(chatId: ChatId) = request { session.markRead(chatId.remoteId) }

    suspend fun block(chatId: ChatId) = request { session.block(chatId.remoteId) }

    /** A thread for a phone number: Google Voice makes it on the first send. */
    fun startConversation(phone: String): ChatId {
        val digits = phone.filter { it.isDigit() || it == '+' }
        val e164 = if (digits.startsWith("+")) digits else "+$digits"
        val thread = "t.$e164"
        events.trySend(ConnectorEvent.ChatUpdated(accountId, go.directChat(thread)))
        return go.chatId(thread)
    }

    suspend fun download(
        attachment: Attachment,
        target: File,
    ): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        val media =
            gvJson.decodeFromString(
                GvMedia.serializer(),
                requireNotNull(attachment.remoteRef) { "nothing to fetch" },
            )
        request { session.download(media.id, target.absolutePath) }
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
                if (GvError.codeOf(e) == GvError.LOGGED_OUT) {
                    events.trySend(GvEvent.LoggedOut(e.message.orEmpty()))
                    throw ActionNeededException(
                        "Google has signed PingMe out of Google Voice. Sign in again.",
                        GVOICE_PACKAGE,
                    )
                }
                throw e
            }
        }

    private fun sendFailure(e: Exception) =
        when (GvError.codeOf(e)) {
            GvError.NOT_CONNECTED -> "Not connected to Google Voice"
            GvError.REJECTED -> "Google Voice refused to send it"
            else -> "Could not send: ${e.message.orEmpty().substringAfter(": ")}"
        }

    private companion object {
        const val TAG = "PingMeGvoice"
        const val WIDE_PAGE = 4
        const val GVOICE_PACKAGE = "package:com.google.android.apps.googlevoice"
    }
}
