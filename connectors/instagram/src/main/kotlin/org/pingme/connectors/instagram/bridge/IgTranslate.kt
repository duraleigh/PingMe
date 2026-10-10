// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram.bridge

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
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.LinkPreview
import org.pingme.core.model.LinkPreviewSource
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
 * Turns what the Go bridge reports into PingMe's model for one account (BUILD_PLAN.md
 * Phase 6, network 2): threads into chats with their folder (Primary, General, or
 * Requests; UI_DESIGN.md 6.4), messages into messages, and events into [ConnectorEvent]s.
 * It remembers every thread it has seen and which messages it has seen, so a repeat is an
 * update and a read marker can name its message's time.
 *
 * Ids: a chat is its thread fbid; a message is "<thread>/<message id>"; a person is their
 * messaging user id.
 */
@Suppress("TooManyFunctions") // One function per shape that crosses the bridge, plus the lookups the session needs.
class IgTranslate(
    private val accountId: AccountId,
) {
    private val threads = HashMap<String, IgThread>()
    private val seen = LinkedHashMap<String, Seen>()
    private val names = HashMap<String, IgUser>()
    private val previewImages = HashMap<String, String>()

    class Seen(
        val thread: String,
        val sender: String,
        val fromMe: Boolean,
        val timestamp: Long,
        val status: MessageStatus,
    )

    @Volatile var ownId: String = ""

    fun parse(json: String): IgEvent = igJson.decodeFromString(IgEvent.serializer(), json)

    fun threadJson(json: String): IgThread = igJson.decodeFromString(IgThread.serializer(), json)

    fun pageJson(json: String): IgThreadPage = igJson.decodeFromString(IgThreadPage.serializer(), json)

    fun messageJson(json: String): IgMessage = igJson.decodeFromString(IgMessage.serializer(), json)

    fun messagesJson(json: String): List<IgMessage> =
        igJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(IgMessage.serializer()), json)

    fun chatId(thread: String): ChatId = accountId.chat(thread)

    fun messageId(
        thread: String,
        id: String,
    ): MessageId = accountId.message("$thread/$id")

    fun personId(user: String): PersonId = accountId.person(user)

    @Synchronized
    fun seen(id: MessageId): Seen? = seen[id.value.substringAfter('/')]

    @Synchronized
    fun knows(thread: String) = thread in threads

    /**
     * Whether a thread's folder is known: a thread seen only through a message, or fetched on
     * its own (system='INBOX' and nothing else), has none yet (owner, 2026-10-09: Tony Wijaya).
     */
    @Synchronized
    fun knowsFolder(thread: String): Boolean = threads[thread]?.let { folderOrUnknown(it) } != null

    /** Whether a sender has been seen in a thread listing, so they have a name. */
    @Synchronized
    fun knowsPerson(user: String) = user == ownId || user in names

    /** The picture of a shared post or reel, once fetched to the phone; the card shows it. */
    @Synchronized
    fun rememberPreview(
        thread: String,
        id: String,
        path: String,
    ) {
        previewImages["$thread/$id"] = path
    }

    /** Whether a message carries a share whose picture is still to fetch. */
    @Synchronized
    fun previewToFetch(msg: IgMessage): String? =
        msg.share?.previewUrl?.takeIf { it.isNotEmpty() && "${msg.thread}/${msg.id}" !in previewImages }

    /** The folder a thread sits in (UI_DESIGN.md 6.4). */
    fun folderOf(thread: IgThread): ChatFolder = folderOf(thread.systemFolder, thread.folder, thread.folderTag)

    /**
     * The folder when the thread says which, else null. A thread fetched on its own, or handed
     * over with a live message, carries system='INBOX' and nothing else: that says it is not a
     * request, not that it is Primary. Reading it as Primary moved General chats into the inbox
     * at every new message (owner, 2026-10-07: "three more general folder messages in my
     * PingMe inbox this morning"). Only the folder field or a tag tells Primary from General.
     */
    fun folderOrUnknown(thread: IgThread): ChatFolder? =
        when {
            thread.systemFolder in REQUEST_FOLDERS -> ChatFolder.REQUESTS
            thread.folder.isNotEmpty() || thread.folderTag.isNotEmpty() -> folderOf(thread)
            else -> null
        }

    /** Data events become connector events; control events return nothing. */
    @Synchronized
    fun translate(event: IgEvent): List<ConnectorEvent> =
        when (event) {
            is IgEvent.Thread -> threadEvents(event.thread)
            is IgEvent.Message -> messageEvents(event.message)
            is IgEvent.Reaction -> reactionEvents(event)
            is IgEvent.Edit -> editEvents(event)
            is IgEvent.Unsent -> unsentEvents(event)
            is IgEvent.ThreadGone -> listOf(ConnectorEvent.ChatRemoved(accountId, chatId(event.thread)))
            is IgEvent.ReadByMe -> readByMeEvents(event.thread, read = true)
            is IgEvent.UnreadByMe -> readByMeEvents(event.thread, read = false)
            is IgEvent.ReadReceipt -> readReceiptEvents(event)
            is IgEvent.Folder -> folderEvents(event)
            is IgEvent.Typing -> typingEvents(event)
            else -> emptyList()
        }

    /** A thread as a chat, remembered for later lookups, with its newest messages as history. */
    @Synchronized
    fun chat(thread: IgThread): ChatSnapshot {
        // A thread handed over again without its folder fields (a live update carries the
        // messages, not the folder) keeps the folder it was listed with; without this a
        // General chat jumped to Primary at its next message (owner, 2026-10-06: Carrie).
        val known = threads[thread.id]
        val kept =
            if (known == null) {
                thread
            } else {
                thread.copy(
                    folder = thread.folder.ifEmpty { known.folder },
                    systemFolder = thread.systemFolder.ifEmpty { known.systemFolder },
                    folderTag = thread.folderTag.ifEmpty { known.folderTag },
                )
            }
        threads[thread.id] = kept
        thread.users.forEach { names[it.id] = it }
        if (ownId.isEmpty()) thread.users.firstOrNull { it.isMe }?.let { ownId = it.id }
        return snapshot(kept)
    }

    @Synchronized
    fun message(msg: IgMessage): MessageSnapshot = snapshotOf(msg)

    private fun snapshot(thread: IgThread): ChatSnapshot {
        val others = thread.users.filter { !it.isMe && it.id != ownId }
        val newest = thread.messages.maxByOrNull { it.timestamp }
        val unreadFromOthers = newest != null && newest.sender != ownId && thread.readAt < newest.timestamp
        return ChatSnapshot(
            id = chatId(thread.id),
            accountId = accountId,
            kind = if (thread.isGroup) ChatKind.GROUP else ChatKind.DIRECT,
            title = thread.title.ifBlank { others.joinToString { displayName(it) } }.ifBlank { "Instagram" },
            participants = listOf(me()) + others.map(::person),
            unreadCount = if (thread.markedUnread || unreadFromOthers) 1 else 0,
            lastActivityAt = Instant.fromEpochMilliseconds(thread.lastMessageAt),
            // Unknown stays unknown: the store keeps the folder it has (UI_DESIGN.md 6.4).
            folder = folderOrUnknown(thread),
            spaceId = null,
            networkRemoteId = thread.id,
        )
    }

    private fun me(): Person = Person(personId(ownId.ifEmpty { "me" }), accountId, "You", null, ownId, null, null)

    private fun person(user: IgUser): Person =
        Person(
            id = personId(user.id),
            accountId = accountId,
            displayName = displayName(user),
            phoneNumber = null,
            networkHandle = user.username.ifEmpty { user.id },
            // Instagram's profile picture, fetched into app storage by the service (owner, Phase 7).
            avatarPath = user.picture.ifEmpty { null },
            contactId = null,
        )

    private fun displayName(user: IgUser): String =
        when {
            user.isMe || user.id == ownId -> "You"
            user.name.isNotBlank() -> user.name
            user.username.isNotBlank() -> "@${user.username}"
            else -> user.id
        }

    private fun personOf(id: String): Person =
        when {
            id == ownId -> me()
            else -> names[id]?.let(::person) ?: Person(personId(id), accountId, id, null, id, null, null)
        }

    private fun threadEvents(thread: IgThread): List<ConnectorEvent> {
        val chat = chat(thread)
        // A listing can hand a message over without its id (a shared post or reel; owner,
        // 2026-10-07): it cannot be stored under a usable id, and the catch-up fetch on opening
        // the chat brings it with one.
        val plain = thread.messages.filter { it.kind != "system" && it.id.isNotBlank() }
        val batch = ConnectorEvent.HistoryBatch(accountId, chat.id, plain.map { snapshotOf(it) }, complete = false)
        return listOf(ConnectorEvent.ChatUpdated(accountId, chat), batch)
    }

    private fun messageEvents(msg: IgMessage): List<ConnectorEvent> {
        if (msg.kind == "system") return emptyList()
        val before = seen["${msg.thread}/${msg.id}"]
        val snapshot = snapshotOf(msg)
        val main =
            if (before != null) {
                ConnectorEvent.MessageUpdated(accountId, snapshot)
            } else {
                ConnectorEvent.NewMessage(accountId, snapshot)
            }
        // A thread not listed yet (a brand-new request) gets a placeholder until its listing comes.
        return listOf(main)
    }

    private fun snapshotOf(msg: IgMessage): MessageSnapshot {
        val fromMe = msg.sender == ownId
        val sender = personOf(msg.sender)
        val sentAt = Instant.fromEpochMilliseconds(msg.timestamp)
        val attachments = attachmentsOf(msg)
        val status =
            bestOf(seen["${msg.thread}/${msg.id}"]?.status, if (fromMe) MessageStatus.Sent else MessageStatus.Delivered)
        remember(msg, fromMe, status)
        val message =
            Message(
                id = messageId(msg.thread, msg.id),
                chatId = chatId(msg.thread),
                senderId = sender.id,
                sentAt = sentAt,
                receivedAt = sentAt,
                body = bodyOf(msg),
                kind = kindOf(msg, attachments),
                attachments = attachments,
                replyTo = msg.replyTo.takeIf { it.isNotEmpty() }?.let { messageId(msg.thread, it) },
                quote = msg.replyTo.takeIf { it.isNotEmpty() }?.let { Quote("", msg.replyText) },
                editedAt = if (msg.edited) sentAt else null,
                deletedForEveryone = msg.unsent,
                status = status,
                reactions =
                    msg.reactions.map {
                        Reaction(
                            it.emoji,
                            personId(it.sender),
                            Instant.fromEpochMilliseconds(it.timestamp),
                        )
                    },
                transport = Transport.NETWORK,
                networkRemoteId = msg.id,
                linkPreview = previewOf(msg),
                isOutgoing = fromMe,
            )
        return MessageSnapshot(message, sender)
    }

    private fun bodyOf(msg: IgMessage): String? =
        when {
            msg.viewOnceGone.isNotEmpty() -> VIEW_ONCE_GONE
            msg.kind == "unsupported" -> msg.text.ifEmpty { UNSUPPORTED }
            msg.kind == "share" -> msg.text.ifEmpty { null } ?: msg.share?.url?.ifEmpty { null }
            else -> msg.text.ifEmpty { null }
        }

    // A shared post or reel shows as a card with its title and picture (UI_DESIGN.md 10.12).
    private fun previewOf(msg: IgMessage): LinkPreview? {
        val share = msg.share ?: return null
        if (share.url.isEmpty() && share.title.isEmpty()) return null
        val url = share.url.ifEmpty { "https://www.instagram.com/" }
        return LinkPreview(
            url,
            url,
            share.title.ifEmpty {
                null
            },
            share.subtitle.ifEmpty { null },
            previewImages["${msg.thread}/${msg.id}"],
            Instant.fromEpochMilliseconds(
                msg.timestamp,
            ),
            LinkPreviewSource.NETWORK,
        )
    }

    private fun attachmentsOf(msg: IgMessage): List<Attachment> =
        msg.media.filter { it.url.isNotEmpty() || it.id.isNotEmpty() }.mapIndexed { i, media ->
            val kind =
                when (media.kind) {
                    "video" -> AttachmentKind.VIDEO
                    "gif" -> AttachmentKind.GIF
                    "voice" -> AttachmentKind.VOICE
                    "sticker" -> AttachmentKind.STICKER
                    else -> AttachmentKind.IMAGE
                }
            Attachment(
                id = accountId.attachment("${msg.thread}/${msg.id}/$i"),
                kind = kind,
                mimeType = media.mime.ifEmpty { if (kind == AttachmentKind.VIDEO) "video/mp4" else "image/jpeg" },
                fileName = null,
                sizeBytes = 0,
                localPath = null,
                remoteRef = igJson.encodeToString(IgMedia.serializer(), media.copy(thread = msg.thread)),
                durationMs = media.durationMs.takeIf { it > 0 }?.toLong(),
                width = media.width.takeIf { it > 0 },
                height = media.height.takeIf { it > 0 },
                isEphemeral = msg.viewOnce,
                savedAt = null,
            )
        }

    private fun kindOf(
        msg: IgMessage,
        attachments: List<Attachment>,
    ): MessageKind =
        when (attachments.firstOrNull()?.kind) {
            AttachmentKind.IMAGE -> MessageKind.IMAGE
            AttachmentKind.VIDEO -> MessageKind.VIDEO
            AttachmentKind.GIF -> MessageKind.GIF
            AttachmentKind.VOICE -> MessageKind.VOICE
            AttachmentKind.STICKER -> MessageKind.STICKER
            else -> if (msg.unsent) MessageKind.DELETED else MessageKind.TEXT
        }

    private fun remember(
        msg: IgMessage,
        fromMe: Boolean,
        status: MessageStatus,
    ) {
        val key = "${msg.thread}/${msg.id}"
        seen.remove(key)
        seen[key] = Seen(msg.thread, msg.sender, fromMe, msg.timestamp, status)
        while (seen.size > REMEMBERED_MESSAGES) seen.remove(seen.keys.first())
    }

    private fun reactionEvents(event: IgEvent.Reaction): List<ConnectorEvent> {
        val reaction =
            Reaction(
                event.reaction.emoji,
                personId(event.reaction.sender),
                Instant.fromEpochMilliseconds(event.reaction.timestamp),
            )
        val target =
            messageId(
                event.thread.ifEmpty {
                    seen.entries
                        .firstOrNull { it.key.endsWith("/${event.message}") }
                        ?.value
                        ?.thread
                        .orEmpty()
                },
                event.message,
            )
        return listOf(ConnectorEvent.ReactionChanged(accountId, target, reaction, removed = event.removed))
    }

    private fun editEvents(event: IgEvent.Edit): List<ConnectorEvent> {
        val at = Instant.fromEpochMilliseconds(event.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis())
        return listOf(
            ConnectorEvent.MessageEdited(
                accountId,
                chatId(event.thread),
                messageId(event.thread, event.message),
                event.text,
                at,
            ),
        )
    }

    private fun unsentEvents(event: IgEvent.Unsent): List<ConnectorEvent> {
        seen.remove("${event.thread}/${event.message}")
        return listOf(
            ConnectorEvent.MessageRevoked(accountId, chatId(event.thread), messageId(event.thread, event.message)),
        )
    }

    private fun readByMeEvents(
        thread: String,
        read: Boolean,
    ): List<ConnectorEvent> {
        val known = threads[thread] ?: return emptyList()
        val updated =
            if (read) {
                known.copy(
                    markedUnread = false,
                    readAt = System.currentTimeMillis(),
                )
            } else {
                known.copy(markedUnread = true)
            }
        threads[thread] = updated
        return listOf(ConnectorEvent.ChatUpdated(accountId, snapshot(updated)))
    }

    // The other side read up to a time: every message of yours before it is read.
    private fun readReceiptEvents(event: IgEvent.ReadReceipt): List<ConnectorEvent> {
        if (event.sender == ownId) return readByMeEvents(event.thread, read = true)
        return seen.entries
            .filter { it.value.thread == event.thread && it.value.fromMe && it.value.timestamp <= event.timestamp }
            .filter { it.value.status != MessageStatus.Read }
            .map { (key, before) ->
                seen[key] = Seen(before.thread, before.sender, before.fromMe, before.timestamp, MessageStatus.Read)
                ConnectorEvent.StatusChanged(accountId, accountId.message(key), MessageStatus.Read)
            }
    }

    private fun folderEvents(event: IgEvent.Folder): List<ConnectorEvent> {
        val known = threads[event.thread] ?: return emptyList()
        val updated =
            known.copy(
                folder =
                    event.inboxFolder.ifEmpty {
                        known.folder
                    },
                systemFolder = event.folder.ifEmpty { known.systemFolder },
            )
        threads[event.thread] = updated
        return listOf(ConnectorEvent.ChatUpdated(accountId, snapshot(updated)))
    }

    private fun typingEvents(event: IgEvent.Typing): List<ConnectorEvent> =
        listOf(ConnectorEvent.Typing(accountId, chatId(event.thread), personId(event.sender), event.typing))

    companion object {
        private const val REMEMBERED_MESSAGES = 4000
        const val UNSUPPORTED = "This kind of message is not supported yet"
        const val VIEW_ONCE_GONE = "A view-once photo or video that Instagram no longer shows"

        private val REQUEST_FOLDERS = setOf("PENDING", "SPAM", "HIDDEN_REQUESTS")

        /** Requests from the system folder, General from Instagram's own names, else Primary (UI_DESIGN.md 6.4). */
        fun folderOf(
            systemFolder: String,
            folder: String,
            tag: String,
        ): ChatFolder =
            when {
                systemFolder in REQUEST_FOLDERS -> ChatFolder.REQUESTS
                "GENERAL" in folder.uppercase() || "GENERAL" in tag.uppercase() -> ChatFolder.GENERAL
                else -> ChatFolder.PRIMARY
            }

        private val TICK_ORDER =
            listOf(MessageStatus.Sending, MessageStatus.Sent, MessageStatus.Delivered, MessageStatus.Read)

        private fun rank(status: MessageStatus) = TICK_ORDER.indexOf(status).coerceAtLeast(0)

        fun bestOf(
            before: MessageStatus?,
            now: MessageStatus,
        ): MessageStatus =
            if (before == null || now is MessageStatus.Failed || rank(now) >= rank(before)) now else before
    }
}
