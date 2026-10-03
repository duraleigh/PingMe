// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.drinkless.tdlib.TdApi
import org.pingme.connectors.telegram.td.TdError
import org.pingme.connectors.telegram.td.TelegramBridge
import org.pingme.connectors.telegram.td.TelegramClient
import org.pingme.core.connector.ActionNeededException
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
import org.pingme.core.model.MessageStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock

/**
 * One signed-in Telegram account's live client (BUILD_PLAN.md Phase 6, network 2): TDLib's
 * updates become [ConnectorEvent]s, and every request goes through TDLib. TDLib keeps the
 * chats and messages itself, so the chat list is loaded from it on connect and older
 * messages are asked for page by page.
 */
@Suppress("TooManyFunctions") // One function per thing the connector can ask of Telegram.
internal class TelegramSession(
    val accountId: AccountId,
    bridge: TelegramBridge,
    private val dir: File,
    private val apiId: Int,
    private val apiHash: String,
) {
    private val updates = Channel<Any>(Channel.UNLIMITED)
    val go = TelegramTranslate(accountId)
    private val client: TelegramClient = bridge.newClient { updates.trySend(it) }
    private val connected = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)

    /** Opens the client, then streams events until [close] or a failure. */
    fun flow(): Flow<ConnectorEvent> =
        channelFlow {
            try {
                for (update in updates) {
                    when (update) {
                        is TdApi.Object -> handle(update)
                        is ConnectorEvent -> send(update)
                    }
                }
            } finally {
                client.close()
            }
        }

    fun close() {
        updates.close()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per TDLib update that matters.
    private suspend fun ProducerScope<ConnectorEvent>.handle(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                authorization(update.authorizationState)
            }

            is TdApi.UpdateNewChat -> {
                go.remember(update.chat)
            }

            is TdApi.UpdateUser -> {
                go.remember(update.user)
            }

            is TdApi.UpdateChatPosition -> {
                chatChanged(update.chatId) {
                    positions =
                        mergedPositions(positions, update.position)
                }
            }

            is TdApi.UpdateChatLastMessage -> {
                chatChanged(update.chatId) {
                    lastMessage = update.lastMessage
                    if (update.positions.isNotEmpty()) {
                        positions =
                            update.positions
                    }
                }
            }

            is TdApi.UpdateChatTitle -> {
                chatChanged(update.chatId) { title = update.title }
            }

            is TdApi.UpdateChatReadInbox -> {
                chatChanged(update.chatId) {
                    unreadCount = update.unreadCount
                    lastReadInboxMessageId =
                        update.lastReadInboxMessageId
                }
            }

            is TdApi.UpdateChatReadOutbox -> {
                go.chat(update.chatId)?.lastReadOutboxMessageId = update.lastReadOutboxMessageId
                go.readUpTo(update.chatId, update.lastReadOutboxMessageId).forEach {
                    send(ConnectorEvent.StatusChanged(accountId, it, MessageStatus.Read))
                }
            }

            is TdApi.UpdateChatAction -> {
                val user = (update.senderId as? TdApi.MessageSenderUser)?.userId ?: return
                val typing = update.action is TdApi.ChatActionTyping
                send(
                    ConnectorEvent.Typing(
                        accountId,
                        go.chatId(go.chatKeyOf(update.chatId, update.topicId)),
                        go.personId(user),
                        typing,
                    ),
                )
            }

            is TdApi.UpdateNewMessage -> {
                newMessage(update.message)
            }

            is TdApi.UpdateMessageSendSucceeded -> {
                newMessage(update.message)
            }

            is TdApi.UpdateMessageSendFailed -> {
                val key = go.chatKeyOf(update.message.chatId, update.message.topicId)
                send(
                    ConnectorEvent.StatusChanged(
                        accountId,
                        go.messageId(key, update.oldMessageId),
                        MessageStatus.Failed(update.error.message),
                    ),
                )
            }

            is TdApi.UpdateMessageContent -> {
                val key = go.chatKeyOf(update.chatId, null)
                send(
                    ConnectorEvent.MessageEdited(
                        accountId,
                        go.chatId(key),
                        go.messageId(key, update.messageId),
                        go.textOf(update.newContent),
                        Clock.System.now(),
                    ),
                )
            }

            is TdApi.UpdateDeleteMessages -> {
                if (update.isPermanent && !update.fromCache) {
                    val key = go.chatKeyOf(update.chatId, null)
                    update.messageIds.forEach { id ->
                        go.forget(key, id)
                        send(ConnectorEvent.MessageRevoked(accountId, go.chatId(key), go.messageId(key, id)))
                    }
                }
            }

            is TdApi.UpdateMessageInteractionInfo -> {
                val msg =
                    runCatching { client.send(TdApi.GetMessage(update.chatId, update.messageId)) }.getOrNull() ?: return
                msg.interactionInfo = update.interactionInfo
                send(ConnectorEvent.MessageUpdated(accountId, go.message(msg)))
            }

            is TdApi.UpdateForumTopicInfo -> {
                go.rememberTopic(update.info)
                go.chat(update.info.chatId)?.let { chat ->
                    send(ConnectorEvent.SpaceUpdated(accountId, go.space(chat)))
                }
            }

            else -> {
                Unit
            }
        }
    }

    private suspend fun ProducerScope<ConnectorEvent>.authorization(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                client.send(tdlibParameters(dir, apiId, apiHash))
            }

            is TdApi.AuthorizationStateReady -> {
                val me = client.send(TdApi.GetMe())
                go.remember(me)
                go.ownId = me.id
                go.ownPhone =
                    me.phoneNumber
                        .takeIf { it.isNotEmpty() }
                        ?.let { "+$it" }
                        .orEmpty()
                if (!connected.getAndSet(true)) send(ConnectorEvent.State(accountId, ConnectionState.Connected))
                loadChats()
                contacts()
            }

            is TdApi.AuthorizationStateWaitPhoneNumber,
            is TdApi.AuthorizationStateWaitCode,
            is TdApi.AuthorizationStateWaitPassword,
            -> {
                throw ActionNeededException("Telegram has signed PingMe out. Sign in again.", TELEGRAM_PACKAGE)
            }

            is TdApi.AuthorizationStateClosed -> {
                updates.close()
            }

            else -> {
                Unit
            }
        }
    }

    /** Pulls the whole main chat list into TDLib's cache, then sends every listed chat. */
    private suspend fun ProducerScope<ConnectorEvent>.loadChats() {
        syncChats().forEach { send(ConnectorEvent.ChatUpdated(accountId, it)) }
        // Chats an earlier build listed for a "joined Telegram" note alone go away.
        go.allChats().filter { go.inMainList(it) && go.noticeOnly(it) }.forEach {
            send(ConnectorEvent.ChatRemoved(accountId, go.chatId(go.chatKeyOf(it.id, null))))
        }
        spaces().forEach { send(ConnectorEvent.SpaceUpdated(accountId, it)) }
        loaded.set(true)
    }

    /** Asks TDLib to load the main list until it says there is no more (404), then reads it. */
    private suspend fun listedChats(): List<TdApi.Chat> {
        while (true) {
            try {
                client.send(TdApi.LoadChats(TdApi.ChatListMain(), CHAT_PAGE))
            } catch (e: TdError) {
                if (e.code != NOT_FOUND) Log.w(TAG, "Could not load the chat list: ${e.message}")
                break
            }
        }
        val ids = client.send(TdApi.GetChats(TdApi.ChatListMain(), CHAT_LIST_MAX)).chatIds
        val chats = ArrayList<TdApi.Chat>()
        for (id in ids) {
            val chat = runCatching { client.send(TdApi.GetChat(id)) }.getOrNull() ?: continue
            go.remember(chat)
            if (go.listed(chat)) chats += chat
        }
        return chats
    }

    private suspend fun ProducerScope<ConnectorEvent>.chatChanged(
        chatId: Long,
        change: TdApi.Chat.() -> Unit,
    ) {
        val chat = go.chat(chatId) ?: return
        chat.change()
        if (!loaded.get()) return
        if (go.listed(chat)) {
            send(ConnectorEvent.ChatUpdated(accountId, go.snapshot(chat)))
        } else if (go.noticeOnly(chat) && go.knows(chatId)) {
            send(ConnectorEvent.ChatRemoved(accountId, go.chatId(go.chatKeyOf(chatId, null))))
        }
    }

    private fun mergedPositions(
        positions: Array<TdApi.ChatPosition>,
        position: TdApi.ChatPosition,
    ): Array<TdApi.ChatPosition> =
        (
            positions.filter {
                it.list.javaClass != position.list.javaClass
            } + position
        ).toTypedArray()

    private suspend fun ProducerScope<ConnectorEvent>.newMessage(msg: TdApi.Message) {
        val chat =
            go.chat(msg.chatId)
                ?: runCatching { client.send(TdApi.GetChat(msg.chatId)) }.getOrNull()?.also { go.remember(it) }
        if (chat != null && !go.inMainList(chat)) return
        // "X joined Telegram" alone makes no chat; the chat appears when someone writes. It
        // does mean a new person for the new-chat list, so the contacts are read again.
        if (msg.content is TdApi.MessageContactRegistered) {
            contacts()
            if (chat != null && go.noticeOnly(chat)) return
        }
        if (chat != null && !go.knows(msg.chatId)) send(ConnectorEvent.ChatUpdated(accountId, go.snapshot(chat)))
        send(ConnectorEvent.NewMessage(accountId, go.message(msg)))
    }

    private suspend fun ProducerScope<ConnectorEvent>.contacts() {
        try {
            val ids = client.send(TdApi.GetContacts()).userIds.toList()
            ids.forEach { id -> if (go.people(listOf(id)).isEmpty()) go.remember(client.send(TdApi.GetUser(id))) }
            send(ConnectorEvent.PeopleUpdated(accountId, go.people(ids)))
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Could not read Telegram's contacts", e)
        }
    }

    /** The main list's chats, with group members looked up, and a forum's topics as chats. */
    suspend fun syncChats(): List<ChatSnapshot> {
        val out = ArrayList<ChatSnapshot>()
        for (chat in listedChats()) {
            members(chat)
            if (chat.viewAsTopics) {
                topics(chat).forEach { out += go.topicSnapshot(chat, it) }
            } else {
                out += go.snapshot(chat)
            }
        }
        return out
    }

    private fun spaces() = go.allChats().filter { go.listed(it) && it.viewAsTopics }.map { go.space(it) }

    private suspend fun members(chat: TdApi.Chat) {
        val ids =
            try {
                when (val type = chat.type) {
                    is TdApi.ChatTypeBasicGroup -> {
                        client.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)).members.mapNotNull {
                            (it.memberId as? TdApi.MessageSenderUser)?.userId
                        }
                    }

                    is TdApi.ChatTypeSupergroup -> {
                        if (type.isChannel) {
                            emptyList()
                        } else {
                            client
                                .send(
                                    TdApi.GetSupergroupMembers(
                                        type.supergroupId,
                                        TdApi.SupergroupMembersFilterRecent(),
                                        0,
                                        MEMBER_PAGE,
                                    ),
                                ).members
                                .mapNotNull {
                                    (it.memberId as? TdApi.MessageSenderUser)?.userId
                                }
                        }
                    }

                    else -> {
                        return
                    }
                }
            } catch (e: TdError) {
                Log.i(TAG, "No member list for ${chat.id}: ${e.message}")
                return
            }
        ids.forEach { id ->
            if (go
                    .people(
                        listOf(id),
                    ).isEmpty()
            ) {
                runCatching { client.send(TdApi.GetUser(id)) }.getOrNull()?.let(go::remember)
            }
        }
        go.rememberMembers(chat.id, ids)
    }

    private suspend fun topics(chat: TdApi.Chat): List<TdApi.ForumTopic> =
        try {
            val page = client.send(TdApi.GetForumTopics(chat.id, "", 0, 0L, 0, TOPIC_PAGE))
            page.topics.forEach { go.rememberTopic(it.info) }
            page.topics.toList()
        } catch (e: TdError) {
            Log.i(TAG, "No topics for ${chat.id}: ${e.message}")
            emptyList()
        }

    /** Up to [limit] messages older than [before] (the newest when null), newest first. */
    suspend fun syncMessages(
        chatId: ChatId,
        before: MessageId?,
        limit: Int,
    ): List<MessageSnapshot> {
        val key = chatId.remoteId
        val (id, topic) = go.parseKey(key)
        var from = before?.remoteId?.substringAfterLast('/')?.toLongOrNull() ?: 0L
        val out = ArrayList<TdApi.Message>()
        // TDLib may answer with fewer than asked (even one) while it fetches; ask again until a page is full or empty.
        var rounds = 0
        while (out.size < limit && rounds < HISTORY_ROUNDS) {
            val page =
                if (topic > 0) {
                    client.send(TdApi.GetForumTopicHistory(id, topic, from, 0, limit - out.size)).messages
                } else {
                    client.send(TdApi.GetChatHistory(id, from, 0, limit - out.size, false)).messages
                }
            if (page.isEmpty()) break
            out += page
            from = page.last().id
            rounds++
        }
        return out.sortedByDescending { it.id }.map { go.message(it) }
    }

    suspend fun send(
        chatId: ChatId,
        draft: OutgoingMessage,
        progress: (Float) -> Unit,
    ): SendResult {
        val (id, topic) = go.parseKey(chatId.remoteId)
        val replyTo =
            draft.replyTo?.remoteId?.substringAfterLast('/')?.toLongOrNull()?.let {
                TdApi.InputMessageReplyToMessage(it, null, 0, "")
            }
        val topicId = if (topic > 0) TdApi.MessageTopicForum(topic) else null
        return try {
            val files = draft.attachments
            if (files.isEmpty()) {
                val content = TdApi.InputMessageText(formatted(draft.body.orEmpty()), null, false)
                val sent = client.send(TdApi.SendMessage(id, topicId, replyTo, null, null, content))
                SendResult.Sent(go.message(sent))
            } else {
                var first: MessageSnapshot? = null
                files.forEachIndexed { i, file ->
                    val caption = if (i == 0) draft.body.orEmpty() else ""
                    val sent =
                        client.send(
                            TdApi.SendMessage(
                                id,
                                topicId,
                                if (i ==
                                    0
                                ) {
                                    replyTo
                                } else {
                                    null
                                },
                                null,
                                null,
                                inputContent(file, caption),
                            ),
                        )
                    val snapshot = go.message(sent).keepingFile(file)
                    progress((i + 1).toFloat() / files.size)
                    if (first ==
                        null
                    ) {
                        first = snapshot
                    } else {
                        updates.trySend(ConnectorEvent.NewMessage(accountId, snapshot))
                    }
                }
                SendResult.Sent(requireNotNull(first))
            }
        } catch (e: TdError) {
            Log.w(TAG, "send to $id failed: ${e.message}")
            SendResult.Failed(
                "Telegram did not take it: ${e.message.orEmpty().substringAfter(": ")}",
                retryable =
                    e.code != FORBIDDEN && e.code != BAD_REQUEST,
            )
        }
    }

    private fun inputContent(
        file: OutgoingAttachment,
        caption: String,
    ): TdApi.InputMessageContent {
        val local = TdApi.InputFileLocal(file.localPath)
        val text = formatted(caption)
        val seconds = ((file.durationMs ?: 0L) / MILLIS).toInt()
        return when (file.kind) {
            AttachmentKind.IMAGE -> {
                TdApi.InputMessagePhoto(
                    TdApi.InputPhoto(local, null, null, IntArray(0), 0, 0),
                    text,
                    false,
                    null,
                    false,
                )
            }

            AttachmentKind.VIDEO -> {
                TdApi.InputMessageVideo(
                    TdApi.InputVideo(local, null, null, 0, IntArray(0), seconds, 0, 0, true),
                    text,
                    false,
                    null,
                    false,
                )
            }

            AttachmentKind.GIF -> {
                TdApi.InputMessageAnimation(
                    TdApi.InputAnimation(local, null, IntArray(0), seconds, 0, 0),
                    text,
                    false,
                    false,
                )
            }

            AttachmentKind.VOICE, AttachmentKind.AUDIO -> {
                TdApi.InputMessageVoiceNote(
                    TdApi.InputVoiceNote(local, seconds, ByteArray(0)),
                    text,
                    null,
                )
            }

            else -> {
                TdApi.InputMessageDocument(TdApi.InputDocument(local, null, false), text)
            }
        }
    }

    private fun formatted(text: String) = TdApi.FormattedText(text, emptyArray())

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
        return copy(message = message.copy(attachments = listOf(kept)))
    }

    suspend fun react(
        messageId: MessageId,
        emoji: String?,
        remove: Boolean,
    ) {
        val (id, msg) = ids(messageId)
        if (remove) {
            val named = emoji ?: chosenReaction(id, msg) ?: return
            client.send(TdApi.RemoveMessageReaction(id, msg, TdApi.ReactionTypeEmoji(named)))
        } else {
            client.send(TdApi.AddMessageReaction(id, msg, TdApi.ReactionTypeEmoji(emoji.orEmpty()), false, false))
        }
    }

    private suspend fun chosenReaction(
        chatId: Long,
        messageId: Long,
    ): String? =
        runCatching { client.send(TdApi.GetMessage(chatId, messageId)) }
            .getOrNull()
            ?.interactionInfo
            ?.reactions
            ?.reactions
            ?.firstOrNull { it.isChosen }
            ?.let { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }

    suspend fun delete(messageId: MessageId) {
        val (id, msg) = ids(messageId)
        client.send(TdApi.DeleteMessages(id, longArrayOf(msg), true))
    }

    suspend fun edit(
        messageId: MessageId,
        text: String,
    ) {
        val (id, msg) = ids(messageId)
        client.send(TdApi.EditMessageText(id, msg, null, TdApi.InputMessageText(formatted(text), null, false)))
    }

    suspend fun markRead(
        chatId: ChatId,
        upTo: MessageId,
    ) {
        val (id, _) = go.parseKey(chatId.remoteId)
        val msg = upTo.remoteId.substringAfterLast('/').toLongOrNull() ?: return
        client.send(TdApi.ViewMessages(id, longArrayOf(msg), null, true))
    }

    suspend fun typing(
        chatId: ChatId,
        typing: Boolean,
    ) {
        val (id, topic) = go.parseKey(chatId.remoteId)
        val action: TdApi.ChatAction = if (typing) TdApi.ChatActionTyping() else TdApi.ChatActionCancel()
        client.send(TdApi.SendChatAction(id, if (topic > 0) TdApi.MessageTopicForum(topic) else null, "", action))
    }

    /** A chat with the person behind a phone number, once Telegram says the number has an account. */
    suspend fun startConversation(handle: String): ChatId {
        val user = userFor(handle) ?: throw UnsupportedCapabilityException("$handle is not on Telegram")
        val chat = client.send(TdApi.CreatePrivateChat(user.id, false))
        go.remember(chat)
        updates.trySend(ConnectorEvent.ChatUpdated(accountId, go.snapshot(chat)))
        return go.chatId(chat.id.toString())
    }

    suspend fun createGroup(
        title: String,
        handles: List<String>,
    ): ChatId {
        val ids = handles.mapNotNull { userFor(it)?.id }
        if (ids.isEmpty()) throw UnsupportedCapabilityException("None of those people are on Telegram")
        val made = client.send(TdApi.CreateNewBasicGroupChat(ids.toLongArray(), title, 0))
        val chat = client.send(TdApi.GetChat(made.chatId))
        go.remember(chat)
        go.rememberMembers(chat.id, ids)
        updates.trySend(ConnectorEvent.ChatUpdated(accountId, go.snapshot(chat)))
        return go.chatId(chat.id.toString())
    }

    private suspend fun userFor(handle: String): TdApi.User? =
        try {
            val user =
                handle.toLongOrNull()?.let { client.send(TdApi.GetUser(it)) }
                    ?: client.send(TdApi.SearchUserByPhoneNumber(handle, false))
            go.remember(user)
            user
        } catch (e: TdError) {
            Log.i(TAG, "No Telegram user for $handle: ${e.message}")
            null
        }

    suspend fun block(chatId: ChatId) {
        val (id, _) = go.parseKey(chatId.remoteId)
        val user =
            (go.chat(id)?.type as? TdApi.ChatTypePrivate)?.userId
                ?: throw UnsupportedCapabilityException("Only a person can be blocked")
        client.send(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(user), TdApi.BlockListMain()))
    }

    /** Fetches a file through TDLib (which keeps its own copy), then copies it to [target]. */
    suspend fun download(
        attachment: Attachment,
        target: File,
    ): File {
        if (target.exists()) return target
        target.parentFile?.mkdirs()
        val ref =
            tdJson.decodeFromString(
                TdFileRef.serializer(),
                requireNotNull(attachment.remoteRef) { "nothing to fetch" },
            )
        if (ref.fileId == 0) {
            target.writeText(ref.text)
            return target
        }
        val file = client.send(TdApi.DownloadFile(ref.fileId, DOWNLOAD_PRIORITY, 0, 0, true))
        val path =
            file.local?.path?.takeIf { it.isNotEmpty() && file.local.isDownloadingCompleted }
                ?: throw java.io.IOException("Telegram did not deliver the file")
        File(path).copyTo(target, overwrite = true)
        return target
    }

    private fun ids(messageId: MessageId): Pair<Long, Long> {
        val (chat, _) = go.parseKey(messageId.remoteId.substringBeforeLast('/'))
        return chat to (messageId.remoteId.substringAfterLast('/').toLongOrNull() ?: 0L)
    }

    private companion object {
        const val TAG = "PingMeTelegram"
        const val TELEGRAM_PACKAGE = "package:org.telegram.messenger"
        const val CHAT_PAGE = 100
        const val CHAT_LIST_MAX = 1000
        const val MEMBER_PAGE = 200
        const val TOPIC_PAGE = 100
        const val HISTORY_ROUNDS = 5
        const val NOT_FOUND = 404
        const val FORBIDDEN = 403
        const val BAD_REQUEST = 400
        const val DOWNLOAD_PRIORITY = 16
        const val MILLIS = 1000L
    }
}

/** TDLib's start-up settings for one account's directory. */
internal fun tdlibParameters(
    dir: File,
    apiId: Int,
    apiHash: String,
): TdApi.SetTdlibParameters =
    TdApi.SetTdlibParameters(
        false,
        dir.absolutePath,
        File(dir, "files").absolutePath,
        ByteArray(0),
        true,
        true,
        true,
        false,
        apiId,
        apiHash,
        "en",
        "Android",
        "",
        "PingMe",
    )
