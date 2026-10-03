// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.fbpage

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.pingme.core.connector.ActionNeededException
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * One Page's inbox (BUILD_PLAN.md Phase 6, network 7): PingMe has no server for Meta's
 * webhooks, so the conversations are polled on an interval while the connection service
 * runs; every poll ends with a "checked just now" state (UI_DESIGN.md 6.6). Replies go
 * through the Send API, within Meta's 24-hour window.
 */
@Suppress("TooManyFunctions") // One function per thing the connector can ask of the Page.
internal class FbPageSession(
    val accountId: AccountId,
    private val graph: PageGraph,
    private val token: String,
    pageId: String,
    pageName: String,
    private val pollEvery: Duration,
) {
    val go = FbPageTranslate(accountId, pageId, pageName)
    private val pageId = pageId
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val closed = Channel<Unit>(Channel.CONFLATED)

    /** Reports connected, then polls until [close] (or the token stops working). */
    fun flow(): Flow<ConnectorEvent> =
        channelFlow {
            send(ConnectorEvent.State(accountId, ConnectionState.Connected))
            launch {
                closed.receive()
                close()
            }
            while (true) {
                poll()
                send(ConnectorEvent.State(accountId, ConnectionState.Polled(Clock.System.now())))
                select<Unit> {
                    wake.onReceive { }
                    onTimeout(pollEvery) { }
                }
            }
        }

    fun close() {
        closed.trySend(Unit)
    }

    /** Asks for a poll now rather than at the next tick (after a send, say). */
    fun pollSoon() {
        wake.trySend(Unit)
    }

    private suspend fun ProducerScope<ConnectorEvent>.poll() {
        val conversations =
            try {
                graph.conversations(pageId, token)
            } catch (e: GraphException) {
                if (e.tokenGone) throw ActionNeededException(TOKEN_GONE, null)
                Log.w(TAG, "Meta refused the inbox poll: ${e.message}")
                return
            } catch (e: IOException) {
                Log.i(TAG, "Inbox poll failed: ${e.message}")
                return
            }
        for (conversation in conversations) {
            val changed = go.changed(conversation)
            val fresh = go.fresh(conversation)
            if (changed) send(ConnectorEvent.ChatUpdated(accountId, go.chat(conversation)))
            fresh.forEach { send(ConnectorEvent.NewMessage(accountId, it)) }
        }
    }

    suspend fun syncChats(): List<ChatSnapshot> = request { graph.conversations(pageId, token) }.map { go.chat(it) }

    /** Up to [limit] messages older than [before] (the newest when null), newest first. */
    suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> {
        val conversation = chatId.remoteId
        val anchor = before?.remoteId?.substringAfter('/')
        val anchorAt = before?.let { go.timeOf(it) }
        val found = ArrayList<PageMessage>()
        var cursor = ""
        var pages = 0
        do {
            val page = request { graph.messages(conversation, token, cursor, PAGE) }
            page.messages.forEach { m ->
                val older =
                    anchor == null || (anchorAt != null && m.createdAt < anchorAt) ||
                        (anchorAt == null && m.id != anchor)
                if (older && m.id != anchor) found += m
            }
            cursor = page.nextCursor
            pages++
        } while (found.size < limit && cursor.isNotEmpty() && pages < MAX_PAGES)
        return found
            .sortedByDescending { it.createdAt }
            .take(limit)
            .map { go.message(conversation, it) }
    }

    suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
        progress: (Float) -> Unit,
    ): SendResult {
        val conversation = chatId.remoteId
        val person = personIn(conversation) ?: return SendResult.Failed(NO_PERSON, retryable = true)
        val lastHeard = go.lastFromPerson(conversation)
        if (lastHeard == null || Clock.System.now().toEpochMilliseconds() - lastHeard > WINDOW.inWholeMilliseconds) {
            return SendResult.Failed(WINDOW_CLOSED, retryable = false)
        }
        return try {
            val files = draft.attachments
            var first: MessageSnapshot? = null
            draft.body?.takeIf { it.isNotBlank() }?.let { text ->
                val id = request { graph.sendText(pageId, token, person.id, text) }
                first = go.sent(conversation, id, text, Clock.System.now().toEpochMilliseconds())
            }
            files.forEachIndexed { i, file ->
                val id =
                    request {
                        graph.sendFile(pageId, token, person.id, File(file.localPath), file.mimeType, metaKind(file))
                    }
                val sent = go.sent(conversation, id, "", Clock.System.now().toEpochMilliseconds()).keepingFile(file)
                progress((i + 1).toFloat() / files.size)
                if (first == null) first = sent else extra += sent
            }
            pollSoon()
            SendResult.Sent(first ?: return SendResult.Failed("Nothing to send", retryable = false))
        } catch (e: GraphException) {
            Log.w(TAG, "send to $conversation failed: ${e.message}")
            if (e.windowClosed) {
                SendResult.Failed(
                    WINDOW_CLOSED,
                    retryable = false,
                )
            } else {
                SendResult.Failed(e.message.orEmpty(), retryable = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "send to $conversation failed", e)
            SendResult.Failed("Could not reach Facebook: ${e.message}", retryable = true)
        }
    }

    /** Files sent after the first one, which the connector reports as their own messages. */
    val extra = ArrayList<MessageSnapshot>()

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

    /** The person in a conversation, listing the inbox first when it has not been seen yet. */
    private suspend fun personIn(conversation: String): PageUser? {
        go.personIn(conversation)?.let { return it }
        try {
            request { graph.conversations(pageId, token) }.forEach { go.chat(it) }
        } catch (e: IOException) {
            Log.i(TAG, "Could not list the inbox: ${e.message}")
        }
        return go.personIn(conversation)
    }

    /** Meta sees the chat as read ("mark_seen"); it has no per-message read marker. */
    suspend fun markRead(chatId: ChatId) {
        val person = go.personIn(chatId.remoteId) ?: return
        try {
            request { graph.senderAction(pageId, token, person.id, "mark_seen") }
        } catch (e: IOException) {
            Log.i(TAG, "mark_seen failed: ${e.message}")
        }
    }

    suspend fun download(
        attachment: Attachment,
        target: File,
    ): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        request { graph.download(requireNotNull(attachment.remoteRef) { "nothing to fetch" }, target) }
        return target
    }

    /** Runs a call; a dead token ends the account's connection with a sign-in prompt. */
    private suspend fun <T> request(block: suspend () -> T): T =
        try {
            block()
        } catch (e: GraphException) {
            if (e.tokenGone) throw ActionNeededException(TOKEN_GONE, null)
            throw e
        }

    private fun messageKindOf(kind: AttachmentKind) =
        when (kind) {
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.VOICE, AttachmentKind.AUDIO -> MessageKind.VOICE
            else -> MessageKind.FILE
        }

    /** Meta's attachment types: image, video, audio, or file. */
    private fun metaKind(file: OutgoingAttachment) =
        when (file.kind) {
            AttachmentKind.IMAGE, AttachmentKind.GIF, AttachmentKind.STICKER -> "image"
            AttachmentKind.VIDEO -> "video"
            AttachmentKind.VOICE, AttachmentKind.AUDIO -> "audio"
            else -> "file"
        }

    companion object {
        const val TAG = "PingMeFbPage"
        private const val PAGE = 25
        private const val MAX_PAGES = 4
        val DEFAULT_POLL = 20.seconds
        private val WINDOW = 24.hours
        const val TOKEN_GONE = "Facebook no longer accepts this Page token. Paste a new one."
        const val WINDOW_CLOSED = "Meta's 24-hour reply window for this person has closed"
        const val NO_PERSON = "This conversation has not loaded yet"
    }
}
