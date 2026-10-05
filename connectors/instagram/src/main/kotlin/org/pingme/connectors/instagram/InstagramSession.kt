// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.pingme.connectors.instagram.bridge.IgBridge
import org.pingme.connectors.instagram.bridge.IgError
import org.pingme.connectors.instagram.bridge.IgEvent
import org.pingme.connectors.instagram.bridge.IgMedia
import org.pingme.connectors.instagram.bridge.IgMessage
import org.pingme.connectors.instagram.bridge.IgSession
import org.pingme.connectors.instagram.bridge.IgThread
import org.pingme.connectors.instagram.bridge.IgTranslate
import org.pingme.connectors.instagram.bridge.igJson
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
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One signed-in Instagram account's live connection (BUILD_PLAN.md Phase 6, network 2):
 * the bridge session, the event loop that turns Instagram's events into
 * [ConnectorEvent]s, and every request on it. Refreshed cookies are saved as they come.
 */
@Suppress("TooManyFunctions") // One function per thing the connector can ask of Instagram.
internal class InstagramSession(
    val accountId: AccountId,
    bridge: IgBridge,
    cookiesJson: String,
    private val credentialRef: String,
    private val credentials: CredentialStore,
    private val previewDir: File,
) {
    private val events = Channel<Any>(Channel.UNLIMITED)
    val go = IgTranslate(accountId)
    private val session: IgSession =
        bridge.newSession(cookiesJson) { json ->
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
        // Known before connecting on a pretend network; the real bridge says once the inbox loads.
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
                        is IgEvent -> handle(event)
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

    private suspend fun ProducerScope<ConnectorEvent>.handle(event: IgEvent) {
        when (event) {
            is IgEvent.Connected -> {
                go.ownId = event.id.ifEmpty { go.ownId }
                if (event.cookies.isNotEmpty()) credentials.save(credentialRef, event.cookies.toByteArray())
            }

            is IgEvent.InboxLoaded -> {
                requests().forEach { send(ConnectorEvent.ChatUpdated(accountId, it)) }
                if (!connected.getAndSet(true)) send(ConnectorEvent.State(accountId, ConnectionState.Connected))
            }

            is IgEvent.LoggedOut -> {
                throw ActionNeededException(
                    "Instagram signed PingMe out (${event.error}). Sign in again.",
                    INSTAGRAM_PACKAGE,
                )
            }

            is IgEvent.Resync -> {
                throw IOException("Instagram asked for a fresh start; reconnecting")
            }

            is IgEvent.Disconnected -> {
                Log.i(TAG, "Instagram connection dropped (${event.error}); the bridge reconnects by itself")
            }

            is IgEvent.Live -> {
                Unit
            }

            else -> {
                if (event is IgEvent.Message) placeThread(event.message)
                translated(event).forEach { send(it) }
                fetchPreviews(event)
            }
        }
    }

    /** Folder moves and thread removals go to the phone's log (owner, 2026-10-04: chats vanished). */
    private fun translated(event: IgEvent): List<ConnectorEvent> {
        if (event is IgEvent.Folder || event is IgEvent.ThreadGone) {
            Log.i(TAG, "Instagram $event")
            org.pingme.core.connector.Diag
                .note(TAG, "Instagram $event")
        }
        when (event) {
            is IgEvent.Message -> {
                noteViewOnce(event.message, "live")
            }

            is IgEvent.Thread -> {
                event.thread.messages.forEach { noteViewOnce(it, "listed") }
                if (notPrimary(event.thread)) {
                    noteFolder(event.thread, "as an event")
                }
            }

            // Typing for a thread PingMe has no chat for would show nowhere (owner, 2026-10-05).
            is IgEvent.Typing -> {
                if (!go.knows(event.thread)) {
                    org.pingme.core.connector.Diag
                        .note(TAG, "Typing for an unknown thread ${event.thread} from ${event.sender}")
                }
            }

            else -> {
                Unit
            }
        }
        return go.translate(event)
    }

    /**
     * What a listing page held, and the folder fields of every thread not filed as Primary,
     * for the phone's diagnostic file (owner, 2026-10-05: chats moved Requests to General
     * for no visible reason, and chats were missing from the Instagram list).
     */
    private fun noteListing(
        folder: String,
        page: Int,
        threads: List<IgThread>,
    ) {
        val withMessages = threads.count { it.messages.isNotEmpty() }
        org.pingme.core.connector.Diag
            .note(TAG, "Listed $folder page $page: ${threads.size} threads, $withMessages with messages")
        threads
            .filter {
                notPrimary(it)
            }.forEach { noteFolder(it, "listed from $folder") }
    }

    private fun notPrimary(thread: IgThread) = go.folderOf(thread) != org.pingme.core.model.ChatFolder.PRIMARY

    private fun noteFolder(
        thread: IgThread,
        how: String,
    ) {
        org.pingme.core.connector.Diag.note(
            TAG,
            "Thread ${thread.id} '${thread.title}' $how: folder='${thread.folder}' system='${thread.systemFolder}' " +
                "tag='${thread.folderTag}' -> ${go.folderOf(thread)}",
        )
    }

    /**
     * A view-once message that came without its file goes to the phone's diagnostic file
     * with everything else Instagram said about it (owner, 2026-10-05: unviewed view-once
     * photos showed as gone), so the next one tells us what Instagram sends.
     */
    private fun noteViewOnce(
        msg: IgMessage,
        how: String,
    ) {
        if (msg.viewOnceGone.isEmpty()) return
        org.pingme.core.connector.Diag
            .note(
                TAG,
                "View-once without a file ($how) thread=${msg.thread} id=${msg.id} ${msg.viewOnceGone}: ${msg.raw}",
            )
    }

    /**
     * A message for a thread or from a sender PingMe has not been told about yet: ask
     * Instagram for the thread first, so the chat lands with its name, its people, and its
     * folder (General stays out of All) instead of a bare placeholder named by an id that
     * only the next full sync would fix (owner, Gate G7).
     */

    private suspend fun ProducerScope<ConnectorEvent>.placeThread(msg: IgMessage) {
        if (go.knows(msg.thread) && go.knowsPerson(msg.sender) && go.knowsFolder(msg.thread)) return
        try {
            val thread = go.threadJson(request { session.thread(msg.thread) })
            // Which folder Instagram puts the thread in, for the phone's log (owner, 2026-10-04: General leaked).
            Log.i(
                TAG,
                "Instagram thread fetched: folder='${thread.folder}' system='${thread.systemFolder}' " +
                    "tag='${thread.folderTag}'",
            )
            send(ConnectorEvent.ChatUpdated(accountId, go.chat(thread)))
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Could not fetch the thread ${msg.thread} a message came for", e)
        }
    }

    private fun ProducerScope<ConnectorEvent>.fetchPreviews(event: IgEvent) {
        when (event) {
            is IgEvent.Message -> fetchPreview(event.message)
            is IgEvent.Thread -> event.thread.messages.forEach { fetchPreview(it) }
            else -> Unit
        }
    }

    /**
     * A shared post or reel comes with the address of its picture; the card shows it once
     * the picture is on the phone (owner, Gate G7). Fetched in the background, a few at a
     * time, then the message is reported again with the picture.
     */
    private fun ProducerScope<ConnectorEvent>.fetchPreview(msg: IgMessage) {
        val url = go.previewToFetch(msg) ?: return
        launch(Dispatchers.IO) {
            previews.withPermit {
                val target = File(previewDir, sha("${msg.thread}/${msg.id}") + ".jpg")
                try {
                    if (!target.exists()) {
                        target.parentFile?.mkdirs()
                        session.download(url, target.absolutePath)
                    }
                    go.rememberPreview(msg.thread, msg.id, target.absolutePath)
                    send(ConnectorEvent.MessageUpdated(accountId, go.message(msg)))
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Log.i(TAG, "No picture for a shared post: ${e.message}")
                }
            }
        }
    }

    private val previews = Semaphore(PREVIEW_FETCHES)

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The request queue: threads waiting for a yes or no (UI_DESIGN.md 6.4). */
    private suspend fun requests(): List<ChatSnapshot> =
        try {
            go.pageJson(request { session.listThreads(PENDING, "") }).threads.map { go.chat(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Could not list message requests", e)
            emptyList()
        }

    /**
     * The inbox and the request queue. The inbox is listed newest first, and listing stops
     * once a page holds nothing from the last thirty days: the owner wants recent Primary
     * chats, not the whole inbox (owner, 2026-10-03). The request queue is one page.
     */
    suspend fun syncChats(): List<ChatSnapshot> {
        val found = ArrayList<ChatSnapshot>()
        val cutoff = System.currentTimeMillis() - RECENT_MS
        for (folder in listOf(INBOX, PENDING)) {
            var cursor = ""
            var pages = 0
            do {
                val page =
                    try {
                        go.pageJson(request { session.listThreads(folder, cursor) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        Log.w(TAG, "Could not list $folder", e)
                        break
                    }
                page.threads.mapTo(found) { go.chat(it) }
                // Each listed thread carries its newest messages: they go into the store too,
                // or every chat past the first page sat empty until a message arrived live, and
                // a merged chat's Instagram side was left out of the Instagram list (owner,
                // 2026-10-05: five chats missing from the Instagram inbox).
                page.threads.filter { it.messages.isNotEmpty() }.forEach { events.trySend(IgEvent.Thread(it)) }
                noteListing(folder, pages, page.threads)
                cursor = page.nextCursor
                pages++
                val recent = folder == INBOX && page.threads.any { it.lastMessageAt >= cutoff }
            } while (cursor.isNotEmpty() && pages < MAX_PAGES && recent)
        }
        return found
    }

    /** Up to [limit] messages older than [before] (the newest when null), newest first. */
    suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> {
        val thread = chatId.remoteId
        val anchor = before?.remoteId?.substringAfterLast('/').orEmpty()
        val page = go.messagesJson(request { session.messages(thread, anchor, limit) })
        val messages =
            page
                .filter {
                    it.kind != "system" && it.id != anchor
                }.map { it.copy(thread = it.thread.ifEmpty { thread }) }
        messages.forEach { msg -> go.previewToFetch(msg)?.let { events.trySend(IgEvent.Message(msg)) } }
        return messages.sortedByDescending { it.timestamp }.map { go.message(it) }
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
                ?.substringAfterLast('/')
                .orEmpty()
        return try {
            val files = draft.attachments
            if (files.isEmpty()) {
                val json = request { session.sendText(thread, draft.body.orEmpty(), reply) }
                SendResult.Sent(go.message(go.messageJson(json).copy(thread = thread)))
            } else {
                // Instagram carries one file per message; the text follows as a message of its own.
                var first: MessageSnapshot? = null
                files.forEachIndexed { i, file ->
                    val json =
                        request {
                            session.sendMedia(
                                thread,
                                file.localPath,
                                file.mimeType,
                                kindOf(file),
                                file.fileName.orEmpty(),
                                if (i ==
                                    0
                                ) {
                                    reply
                                } else {
                                    ""
                                },
                            )
                        }
                    val sent = go.message(go.messageJson(json).copy(thread = thread)).keepingFile(file)
                    progress((i + 1).toFloat() / files.size)
                    if (first == null) first = sent else events.trySend(ConnectorEvent.NewMessage(accountId, sent))
                }
                draft.body?.takeIf { it.isNotBlank() }?.let { text ->
                    val json = request { session.sendText(thread, text, "") }
                    events.trySend(
                        ConnectorEvent.NewMessage(accountId, go.message(go.messageJson(json).copy(thread = thread))),
                    )
                }
                SendResult.Sent(requireNotNull(first))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "send to $thread failed", e)
            SendResult.Failed(sendFailure(e), retryable = IgError.codeOf(e) != IgError.REJECTED)
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

    // A message id names its thread ("<thread>/<id>"), so reactions, unsends, and edits need
    // nothing the session remembers: after a restart the session knew nothing of older
    // messages and refused to react to them (owner, 2026-10-05: "wait for the message to
    // finish sending" on a message received long before).
    private fun threadOf(id: MessageId) = id.remoteId.substringBeforeLast('/')

    private fun idOf(id: MessageId) = id.remoteId.substringAfterLast('/')

    suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) {
        request { session.sendReaction(threadOf(messageId), idOf(messageId), emoji ?: "", remove) }
    }

    suspend fun unsend(messageId: MessageId) {
        request { session.unsend(threadOf(messageId), idOf(messageId)) }
    }

    suspend fun edit(
        messageId: MessageId,
        text: String,
    ) {
        request { session.edit(threadOf(messageId), idOf(messageId), text) }
    }

    /**
     * Tells Instagram the chat is read up to [upTo]. The mark needs the message's time; a
     * message from before this start is looked up in a page of the thread (the session
     * dropped read marks for such messages, so Instagram kept them unread; owner, 2026-10-05).
     */
    suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) {
        val at = go.seen(upTo)?.timestamp ?: recalled(upTo)?.timestamp ?: System.currentTimeMillis()
        request { session.markRead(chatId.remoteId, idOf(upTo), at) }
    }

    /** Brings a message from before this start back into the session's memory, from a page of its thread. */
    private suspend fun recalled(id: MessageId): IgTranslate.Seen? {
        val thread = threadOf(id)
        val page =
            try {
                go.messagesJson(request { session.messages(thread, "", RECALL) })
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Could not recall the thread $thread for a read mark", e)
                return null
            }
        page.forEach { go.message(it.copy(thread = it.thread.ifEmpty { thread })) }
        return go.seen(id)
    }

    suspend fun typing(
        chatId: ChatId,
        typing: Boolean,
    ) = request { session.setTyping(chatId.remoteId, typing) }

    suspend fun respondToRequest(
        chatId: ChatId,
        accept: Boolean,
    ) = request { if (accept) session.acceptRequest(chatId.remoteId) else session.deleteThread(chatId.remoteId) }

    suspend fun download(
        attachment: Attachment,
        target: File,
    ): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        val media =
            igJson.decodeFromString(
                IgMedia.serializer(),
                requireNotNull(attachment.remoteRef) { "nothing to fetch" },
            )
        // A kept photo or video can arrive without an address; Instagram gives one by id.
        val url = media.url.ifEmpty { request { session.mediaUrl(media.thread, media.id) } }
        request { session.download(url, target.absolutePath) }
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
                if (IgError.codeOf(e) == IgError.LOGGED_OUT) {
                    events.trySend(IgEvent.LoggedOut(e.message.orEmpty()))
                    throw ActionNeededException("Instagram signed PingMe out. Sign in again.", INSTAGRAM_PACKAGE)
                }
                throw e
            }
        }

    private fun messageKindOf(kind: AttachmentKind) =
        when (kind) {
            AttachmentKind.IMAGE -> org.pingme.core.model.MessageKind.IMAGE
            AttachmentKind.VIDEO -> org.pingme.core.model.MessageKind.VIDEO
            AttachmentKind.GIF -> org.pingme.core.model.MessageKind.GIF
            AttachmentKind.VOICE, AttachmentKind.AUDIO -> org.pingme.core.model.MessageKind.VOICE
            AttachmentKind.STICKER -> org.pingme.core.model.MessageKind.STICKER
            else -> org.pingme.core.model.MessageKind.FILE
        }

    private fun kindOf(file: OutgoingAttachment) =
        when (file.kind) {
            AttachmentKind.VIDEO -> "video"
            AttachmentKind.GIF -> "gif"
            AttachmentKind.VOICE, AttachmentKind.AUDIO -> "voice"
            else -> "image"
        }

    private fun sendFailure(e: Exception) =
        when (IgError.codeOf(e)) {
            IgError.NOT_CONNECTED -> "Not connected to Instagram"
            IgError.REJECTED -> "Instagram refused to send it"
            else -> "Could not send: ${e.message.orEmpty().substringAfter(": ")}"
        }

    private companion object {
        const val TAG = "PingMeInstagram"
        const val INBOX = "INBOX"
        const val PENDING = "PENDING"

        /** A safety cap on pages of about twenty threads; the thirty-day rule normally stops sooner. */
        const val MAX_PAGES = 60

        /** Messages fetched to find one from before this start. */
        const val RECALL = 50

        /** How far back the inbox listing reaches (owner, 2026-10-03: the last thirty days of Primary). */
        const val RECENT_MS = 30L * 24 * 60 * 60 * 1000
        const val INSTAGRAM_PACKAGE = "package:com.instagram.android"
        const val PREVIEW_FETCHES = 2
    }
}
