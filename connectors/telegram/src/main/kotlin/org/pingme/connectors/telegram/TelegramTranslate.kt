// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.MessageSnapshot
import org.pingme.core.connector.attachment
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.connector.space
import org.pingme.core.model.AccountId
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.model.Quote
import org.pingme.core.model.Reaction
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceKind
import org.pingme.core.model.Transport
import kotlin.time.Instant

/** What a file on Telegram's servers needs to be fetched: TDLib's file id. */
@Serializable
data class TdFileRef(
    val fileId: Int,
    val uniqueId: String = "",
    val mime: String = "",
    val name: String = "",
    /** For a shared contact or place, the text written out instead of a download. */
    val text: String = "",
)

internal val tdJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/**
 * Turns TDLib's objects into PingMe's model for one account (BUILD_PLAN.md Phase 6,
 * network 2). It remembers chats, people, and which messages it has seen, so a repeat is
 * an update and a read mark can find the messages it covers.
 *
 * Ids: a chat is TDLib's chat id, or "<chat id>#<topic id>" for a forum topic; a message
 * is "<chat key>/<message id>"; a person is their user id ("chat:<id>" when a chat
 * posts as itself).
 */
@Suppress("TooManyFunctions") // One function per shape that crosses from TDLib, plus the lookups the session needs.
class TelegramTranslate(
    private val accountId: AccountId,
) {
    private val chats = HashMap<Long, TdApi.Chat>()
    private val users = HashMap<Long, TdApi.User>()
    private val members = HashMap<Long, List<Long>>()
    private val topics = HashMap<Long, MutableMap<Int, TdApi.ForumTopicInfo>>()
    private val seen = LinkedHashMap<String, Seen>()

    class Seen(
        val chatKey: String,
        val fromMe: Boolean,
        val messageId: Long,
        val status: MessageStatus,
    )

    @Volatile var ownId: Long = 0

    @Volatile var ownPhone: String = ""

    fun chatId(key: String): ChatId = accountId.chat(key)

    fun messageId(
        chatKey: String,
        id: Long,
    ): MessageId = accountId.message("$chatKey/$id")

    fun personId(userId: Long): PersonId = accountId.person(userId.toString())

    /** The chat key a message belongs to: its forum topic when the chat is a forum. */
    fun chatKeyOf(
        chatId: Long,
        topic: TdApi.MessageTopic?,
    ): String =
        when {
            topic is TdApi.MessageTopicForum && chats[chatId]?.isForum() == true -> "$chatId#${topic.forumTopicId}"
            else -> chatId.toString()
        }

    /** The TDLib chat id and forum topic id behind a chat key. */
    fun parseKey(key: String): Pair<Long, Int> {
        val chat = key.substringBefore('#').toLongOrNull() ?: 0L
        val topic = key.substringAfter('#', "").toIntOrNull() ?: 0
        return chat to topic
    }

    @Synchronized
    fun seen(id: MessageId): Seen? = seen[id.value.substringAfter('/')]

    @Synchronized
    fun knows(chatId: Long) = chatId in chats

    @Synchronized
    fun chat(chatId: Long): TdApi.Chat? = chats[chatId]

    @Synchronized
    fun allChats(): List<TdApi.Chat> = chats.values.toList()

    @Synchronized
    fun remember(chat: TdApi.Chat) {
        chats[chat.id] = chat
    }

    @Synchronized
    fun remember(user: TdApi.User) {
        users[user.id] = user
    }

    @Synchronized
    fun rememberMembers(
        chatId: Long,
        userIds: List<Long>,
    ) {
        members[chatId] = userIds
    }

    @Synchronized
    fun rememberTopic(info: TdApi.ForumTopicInfo) {
        topics.getOrPut(info.chatId) { LinkedHashMap() }[info.forumTopicId] = info
    }

    @Synchronized
    fun topicsOf(chatId: Long): List<TdApi.ForumTopicInfo> = topics[chatId]?.values?.toList().orEmpty()

    /** Whether a chat sits in the main list (archived and unlisted chats stay out). */
    fun listed(chat: TdApi.Chat): Boolean = inMainList(chat) && !noticeOnly(chat)

    fun inMainList(chat: TdApi.Chat): Boolean = chat.positions.any { it.list is TdApi.ChatListMain && it.order != 0L }

    /**
     * A private chat Telegram made only to say "X joined Telegram": not a conversation, so
     * not a chat here until the person actually writes (owner, Gate G7).
     */
    fun noticeOnly(chat: TdApi.Chat): Boolean =
        chat.type is TdApi.ChatTypePrivate && chat.lastMessage?.content is TdApi.MessageContactRegistered

    /** The people the contact list knows, for the new-chat search. */
    @Synchronized
    fun people(userIds: List<Long>): List<Person> =
        userIds.mapNotNull { users[it] }.filter { it.id != ownId }.map(::person)

    /** A chat as PingMe shows it; a forum's topics are chats of their own (UI_DESIGN.md 10.4). */
    @Synchronized
    fun snapshot(chat: TdApi.Chat): ChatSnapshot = snapshotOf(chat, null)

    @Synchronized
    fun topicSnapshot(
        chat: TdApi.Chat,
        topic: TdApi.ForumTopic,
    ): ChatSnapshot = snapshotOf(chat, topic)

    /** A forum as a space holding its topics. */
    @Synchronized
    fun space(chat: TdApi.Chat): Space =
        Space(
            accountId.space(chat.id.toString()),
            accountId,
            chat.title,
            SpaceKind.TELEGRAM_FORUM,
            topicsOf(chat.id).map { chatId("${chat.id}#${it.forumTopicId}") },
        )

    private fun snapshotOf(
        chat: TdApi.Chat,
        topic: TdApi.ForumTopic?,
    ): ChatSnapshot {
        val key = if (topic == null) chat.id.toString() else "${chat.id}#${topic.info.forumTopicId}"
        val isGroup = chat.type !is TdApi.ChatTypePrivate
        val others =
            when (val type = chat.type) {
                is TdApi.ChatTypePrivate -> listOf(type.userId)
                else -> members[chat.id].orEmpty()
            }.filter { it != ownId }
        val last = topic?.lastMessage ?: chat.lastMessage
        return ChatSnapshot(
            id = chatId(key),
            accountId = accountId,
            kind = if (isGroup) ChatKind.GROUP else ChatKind.DIRECT,
            title =
                topic?.info?.name ?: chat.title.ifBlank { others.firstOrNull()?.let { displayName(it) } ?: "Telegram" },
            participants = listOf(me()) + others.map { personOf(it) },
            unreadCount = topic?.unreadCount ?: chat.unreadCount,
            lastActivityAt = Instant.fromEpochMilliseconds((last?.date ?: 0).toLong() * MILLIS),
            folder = if (topic != null) ChatFolder.TOPIC else null,
            spaceId = if (topic != null) accountId.space(chat.id.toString()) else null,
            networkRemoteId = key,
        )
    }

    private fun me(): Person =
        Person(
            personId(ownId),
            accountId,
            "You",
            ownPhone.ifEmpty {
                null
            },
            ownId.toString(),
            null,
            null,
        )

    private fun personOf(userId: Long): Person =
        users[userId]?.let(::person)
            ?: Person(personId(userId), accountId, "Telegram user", null, userId.toString(), null, null)

    private fun person(user: TdApi.User): Person =
        Person(
            id = personId(user.id),
            accountId = accountId,
            displayName = if (user.id == ownId) "You" else displayName(user.id),
            phoneNumber = user.phoneNumber.takeIf { it.isNotEmpty() }?.let { "+$it" },
            networkHandle = user.phoneNumber.takeIf { it.isNotEmpty() }?.let { "+$it" } ?: user.id.toString(),
            avatarPath = null,
            contactId = null,
        )

    private fun displayName(userId: Long): String {
        val user = users[userId] ?: return "Telegram user"
        val name = listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ")
        return name.ifBlank {
            user.usernames
                ?.editableUsername
                ?.takeIf { it.isNotBlank() }
                ?.let { "@$it" }
        }
            ?: user.phoneNumber.takeIf { it.isNotEmpty() }?.let { "+$it" }
            ?: "Telegram user"
    }

    private fun senderOf(sender: TdApi.MessageSender): Person =
        when (sender) {
            is TdApi.MessageSenderUser -> {
                if (sender.userId == ownId) me() else personOf(sender.userId)
            }

            is TdApi.MessageSenderChat -> {
                Person(
                    accountId.person("chat:${sender.chatId}"),
                    accountId,
                    chats[sender.chatId]?.title ?: "Channel",
                    null,
                    "chat:${sender.chatId}",
                    null,
                    null,
                )
            }

            else -> {
                personOf(0)
            }
        }

    /** A TDLib message as PingMe's; the chat's read mark decides whether yours is read. */
    @Synchronized
    fun message(msg: TdApi.Message): MessageSnapshot {
        val key = chatKeyOf(msg.chatId, msg.topicId)
        val sender = senderOf(msg.senderId)
        val sentAt = Instant.fromEpochMilliseconds(msg.date.toLong() * MILLIS)
        val (body, attachment, kind) = contentOf(key, msg)
        val status = statusOf(msg)
        remember(key, msg, status)
        val reply = (msg.replyTo as? TdApi.MessageReplyToMessage)?.takeIf { it.chatId == msg.chatId }
        val message =
            Message(
                id = messageId(key, msg.id),
                chatId = chatId(key),
                senderId = sender.id,
                sentAt = sentAt,
                receivedAt = sentAt,
                body = body,
                kind = kind,
                attachments = listOfNotNull(attachment),
                replyTo = reply?.let { messageId(key, it.messageId) },
                quote = reply?.content?.let { Quote("", textOf(it)) }?.takeIf { it.text.isNotEmpty() },
                editedAt = msg.editDate.takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it.toLong() * MILLIS) },
                deletedForEveryone = false,
                status = status,
                reactions = reactionsOf(msg),
                transport = Transport.NETWORK,
                networkRemoteId =
                    if (msg.sendingState is TdApi.MessageSendingStatePending) {
                        "$STAND_IN${msg.id}"
                    } else {
                        msg.id
                            .toString()
                    },
                linkPreview = null,
                isOutgoing = msg.isOutgoing,
            )
        return MessageSnapshot(message, sender)
    }

    private fun statusOf(msg: TdApi.Message): MessageStatus =
        when {
            !msg.isOutgoing -> {
                MessageStatus.Delivered
            }

            msg.sendingState is TdApi.MessageSendingStatePending -> {
                MessageStatus.Sending
            }

            msg.sendingState is TdApi.MessageSendingStateFailed -> {
                MessageStatus.Failed((msg.sendingState as TdApi.MessageSendingStateFailed).error?.message ?: "Not sent")
            }

            msg.id <= (chats[msg.chatId]?.lastReadOutboxMessageId ?: 0L) -> {
                MessageStatus.Read
            }

            else -> {
                MessageStatus.Sent
            }
        }

    private fun reactionsOf(msg: TdApi.Message): List<Reaction> {
        val at = Instant.fromEpochMilliseconds(msg.date.toLong() * MILLIS)
        return msg.interactionInfo?.reactions?.reactions.orEmpty().flatMap { r ->
            val emoji = (r.type as? TdApi.ReactionTypeEmoji)?.emoji ?: return@flatMap emptyList()
            val senders =
                r.recentSenderIds
                    .orEmpty()
                    .map { senderOf(it).id }
                    .toMutableList()
            if (r.isChosen && personId(ownId) !in senders) senders += personId(ownId)
            if (senders.isEmpty()) senders += PersonId("${accountId.value}/anyone")
            senders.map { Reaction(emoji, it, at) }
        }
    }

    private data class Content(
        val body: String?,
        val attachment: Attachment?,
        val kind: MessageKind,
    )

    // One branch per kind of content TDLib can carry.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun contentOf(
        key: String,
        msg: TdApi.Message,
    ): Content {
        val id = accountId.attachment("$key/${msg.id}")

        fun file(
            f: TdApi.File,
            kind: AttachmentKind,
            mime: String,
            name: String?,
            width: Int = 0,
            height: Int = 0,
            seconds: Int = 0,
        ) = Attachment(
            id = id,
            kind = kind,
            mimeType = mime.ifEmpty { "application/octet-stream" },
            fileName = name?.ifEmpty { null },
            sizeBytes = if (f.size > 0) f.size else f.expectedSize,
            localPath = null,
            remoteRef =
                tdJson.encodeToString(
                    TdFileRef.serializer(),
                    TdFileRef(f.id, f.remote?.uniqueId.orEmpty(), mime, name.orEmpty()),
                ),
            durationMs = seconds.takeIf { it > 0 }?.toLong()?.times(MILLIS),
            width = width.takeIf { it > 0 },
            height = height.takeIf { it > 0 },
            isEphemeral = msg.selfDestructType != null,
            savedAt = null,
        )
        return when (val c = msg.content) {
            is TdApi.MessageText -> {
                Content(c.text.text, null, MessageKind.TEXT)
            }

            is TdApi.MessagePhoto -> {
                val size =
                    c.photo.sizes.maxByOrNull { it.width * it.height }
                        ?: return Content(c.caption.text, null, MessageKind.TEXT)
                Content(
                    c.caption.text.ifEmpty {
                        null
                    },
                    file(
                        size.photo,
                        AttachmentKind.IMAGE,
                        "image/jpeg",
                        null,
                        size.width,
                        size.height,
                    ),
                    MessageKind.IMAGE,
                )
            }

            is TdApi.MessageVideo -> {
                Content(
                    c.caption.text.ifEmpty {
                        null
                    },
                    file(
                        c.video.video,
                        AttachmentKind.VIDEO,
                        c.video.mimeType,
                        c.video.fileName,
                        c.video.width,
                        c.video.height,
                        c.video.duration,
                    ),
                    MessageKind.VIDEO,
                )
            }

            is TdApi.MessageAnimation -> {
                Content(
                    c.caption.text.ifEmpty {
                        null
                    },
                    file(
                        c.animation.animation,
                        AttachmentKind.GIF,
                        c.animation.mimeType,
                        c.animation.fileName,
                        c.animation.width,
                        c.animation.height,
                        c.animation.duration,
                    ),
                    MessageKind.GIF,
                )
            }

            is TdApi.MessageVoiceNote -> {
                Content(
                    c.caption.text.ifEmpty {
                        null
                    },
                    file(
                        c.voiceNote.voice,
                        AttachmentKind.VOICE,
                        c.voiceNote.mimeType,
                        null,
                        seconds = c.voiceNote.duration,
                    ),
                    MessageKind.VOICE,
                )
            }

            is TdApi.MessageAudio -> {
                Content(
                    c.caption.text.ifEmpty {
                        null
                    },
                    file(
                        c.audio.audio,
                        AttachmentKind.AUDIO,
                        c.audio.mimeType,
                        c.audio.fileName,
                        seconds = c.audio.duration,
                    ),
                    MessageKind.VOICE,
                )
            }

            is TdApi.MessageDocument -> {
                Content(
                    c.caption.text.ifEmpty {
                        null
                    },
                    file(
                        c.document.document,
                        AttachmentKind.FILE,
                        c.document.mimeType,
                        c.document.fileName,
                    ),
                    MessageKind.FILE,
                )
            }

            is TdApi.MessageSticker -> {
                Content(
                    c.sticker.emoji.ifEmpty {
                        null
                    },
                    file(
                        c.sticker.sticker,
                        AttachmentKind.STICKER,
                        if (c.sticker.format is TdApi.StickerFormatWebp) "image/webp" else "application/octet-stream",
                        null,
                        c.sticker.width,
                        c.sticker.height,
                    ),
                    MessageKind.STICKER,
                )
            }

            is TdApi.MessageContact -> {
                val name = listOf(c.contact.firstName, c.contact.lastName).filter { it.isNotBlank() }.joinToString(" ")
                val vcard =
                    c.contact.vcard.ifEmpty {
                        "BEGIN:VCARD\nVERSION:3.0\nFN:$name\nTEL:${c.contact.phoneNumber}\nEND:VCARD\n"
                    }
                val att =
                    Attachment(
                        id,
                        AttachmentKind.CONTACT,
                        "text/vcard",
                        "$name.vcf",
                        vcard.length.toLong(),
                        null,
                        tdJson.encodeToString(TdFileRef.serializer(), TdFileRef(0, text = vcard)),
                        null,
                        null,
                        null,
                        false,
                        null,
                    )
                Content(null, att, MessageKind.CONTACT)
            }

            is TdApi.MessageLocation -> {
                val geo = """{"type":"Point","coordinates":[${c.location.longitude},${c.location.latitude}]}"""
                val att =
                    Attachment(
                        id,
                        AttachmentKind.LOCATION,
                        "application/geo+json",
                        null,
                        geo.length.toLong(),
                        null,
                        tdJson.encodeToString(TdFileRef.serializer(), TdFileRef(0, text = geo)),
                        null,
                        null,
                        null,
                        false,
                        null,
                    )
                Content(null, att, MessageKind.LOCATION)
            }

            else -> {
                Content(textOf(c).ifEmpty { UNSUPPORTED }, null, MessageKind.TEXT)
            }
        }
    }

    /** Telegram's own notes in a chat ("X joined", "pinned a message"): a line of words, never "unsupported". */
    @Suppress("CyclomaticComplexMethod") // One line per kind of note Telegram has; a table, not logic.
    fun noticeText(content: TdApi.MessageContent): String? =
        when (content) {
            is TdApi.MessageContactRegistered -> "Joined Telegram"
            is TdApi.MessageChatAddMembers -> "Added members"
            is TdApi.MessageChatJoinByLink, is TdApi.MessageChatJoinByRequest -> "Joined the group"
            is TdApi.MessageChatDeleteMember -> "Left the group"
            is TdApi.MessageChatChangeTitle -> "Changed the group name to ${content.title}"
            is TdApi.MessageChatChangePhoto -> "Changed the group picture"
            is TdApi.MessageChatDeletePhoto -> "Removed the group picture"
            is TdApi.MessageBasicGroupChatCreate -> "Created the group ${content.title}"
            is TdApi.MessageSupergroupChatCreate -> "Created the group ${content.title}"
            is TdApi.MessagePinMessage -> "Pinned a message"
            is TdApi.MessageScreenshotTaken -> "Took a screenshot"
            is TdApi.MessageChatSetTheme, is TdApi.MessageChatSetBackground -> "Changed the chat's look"
            is TdApi.MessageChatSetMessageAutoDeleteTime -> "Changed the auto-delete timer"
            is TdApi.MessageChatUpgradeFrom, is TdApi.MessageChatUpgradeTo -> "Upgraded the group"
            is TdApi.MessageForumTopicCreated -> "Created the topic ${content.name}"
            is TdApi.MessageForumTopicEdited -> "Edited the topic"
            is TdApi.MessageForumTopicIsClosedToggled, is TdApi.MessageForumTopicIsHiddenToggled -> "Changed the topic"
            is TdApi.MessageVideoChatStarted -> "Started a video chat"
            is TdApi.MessageVideoChatEnded -> "Ended the video chat"
            is TdApi.MessageVideoChatScheduled -> "Scheduled a video chat"
            is TdApi.MessageChatBoost -> "Boosted the chat"
            is TdApi.MessageProximityAlertTriggered -> "Is nearby"
            is TdApi.MessageCustomServiceAction -> content.text
            else -> null
        }

    /** The words of any content, for quotes and the chat list. */
    fun textOf(content: TdApi.MessageContent): String =
        when (content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessagePhoto -> content.caption.text.ifEmpty { "Photo" }
            is TdApi.MessageVideo -> content.caption.text.ifEmpty { "Video" }
            is TdApi.MessageAnimation -> content.caption.text.ifEmpty { "GIF" }
            is TdApi.MessageVoiceNote -> "Voice note"
            is TdApi.MessageAudio -> content.audio.title.ifEmpty { "Audio" }
            is TdApi.MessageDocument -> content.document.fileName.ifEmpty { "File" }
            is TdApi.MessageSticker -> content.sticker.emoji.ifEmpty { "Sticker" }
            is TdApi.MessageContact -> "Contact"
            is TdApi.MessageLocation -> "Location"
            is TdApi.MessagePoll -> "📊 " + content.poll.question.text
            else -> noticeText(content).orEmpty()
        }

    private fun remember(
        key: String,
        msg: TdApi.Message,
        status: MessageStatus,
    ) {
        val k = "$key/${msg.id}"
        seen.remove(k)
        seen[k] = Seen(key, msg.isOutgoing, msg.id, status)
        while (seen.size > REMEMBERED_MESSAGES) seen.remove(seen.keys.first())
    }

    /** Your messages in a chat up to [upTo] that are not yet read, by PingMe id. */
    @Synchronized
    fun readUpTo(
        chatId: Long,
        upTo: Long,
    ): List<MessageId> =
        seen.entries
            .filter { (_, s) ->
                s.fromMe && s.messageId <= upTo && s.status != MessageStatus.Read &&
                    s.chatKey.substringBefore('#') == chatId.toString()
            }.map { (k, s) ->
                seen[k] = Seen(s.chatKey, s.fromMe, s.messageId, MessageStatus.Read)
                accountId.message(k)
            }

    @Synchronized
    fun forget(
        key: String,
        id: Long,
    ) {
        seen.remove("$key/$id")
    }

    companion object {
        private const val MILLIS = 1000L
        private const val REMEMBERED_MESSAGES = 4000
        const val STAND_IN = "tmp/"
        const val UNSUPPORTED = "This kind of message is not supported yet"

        private fun TdApi.Chat.isForum(): Boolean = viewAsTopics
    }
}
