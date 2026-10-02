// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.service.NotificationDecision
import org.pingme.core.service.R

/**
 * One conversation notification per chat (UI_DESIGN.md 6.2): the chat is an Android
 * conversation with a person and a shortcut, the unread lines stack in messaging style,
 * Reply and Mark read sit under it, and a tap opens the chat (owner, Gate G2). Media reads
 * as "sent a picture" and the like.
 */
internal class MessageNotifications(
    private val context: Context,
) {
    /** One unread line and the message it is for. */
    private class Line(
        val messageId: org.pingme.core.model.MessageId,
        val text: CharSequence,
        val at: Long,
        val who: Person,
        var picture: Pair<String, android.net.Uri>? = null,
    ) {
        fun styled() =
            NotificationCompat.MessagingStyle
                .Message(text, at, who)
                .apply { picture?.let { (mime, uri) -> setData(mime, uri) } }
    }

    /** What the last post for a chat was, so the notification can be drawn again with a picture. */
    private class Posted(
        val decision: NotificationDecision.Notify,
        val chat: Chat,
        val code: String?,
    )

    /** The unread lines shown per chat, so each new message joins the last ones. */
    private val lines = HashMap<ChatId, MutableList<Line>>()
    private val posted = HashMap<ChatId, Posted>()

    private val manager get() = NotificationManagerCompat.from(context)

    @Synchronized
    fun post(
        decision: NotificationDecision.Notify,
        chat: Chat,
        message: Message,
        sender: org.pingme.core.model.Person?,
        code: String? = null,
    ) {
        val title = chat.nameOverride ?: chat.title
        val who = person(sender?.displayName ?: title, message.senderId.value)
        val text = if (chat.isObscured) context.getString(R.string.notification_new_message) else lineFor(message)
        val history = lines.getOrPut(chat.id) { mutableListOf() }
        if (chat.isObscured) history.clear()
        history += Line(message.id, text, message.sentAt.toEpochMilliseconds(), who)
        while (history.size > MAX_LINES) history.removeAt(0)
        posted[chat.id] = Posted(decision, chat, code)
        publishShortcut(chat, title, who)
        render(chat.id, alert = true)
    }

    /**
     * A picture for a message still in the shade has arrived: the notification shows it
     * (owner, Gate G3), without sounding again.
     */
    @Synchronized
    fun showPicture(
        chatId: ChatId,
        messageId: org.pingme.core.model.MessageId,
        file: java.io.File,
        mimeType: String,
    ) {
        val line = lines[chatId]?.firstOrNull { it.messageId == messageId } ?: return
        val uri =
            runCatching {
                androidx.core.content.FileProvider
                    .getUriForFile(context, "${context.packageName}.files", file)
            }.getOrNull() ?: return
        // The shade is drawn by the system UI, which needs leave to read the file.
        runCatching { context.grantUriPermission(SYSTEM_UI, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        line.picture = mimeType to uri
        render(chatId, alert = false)
    }

    // Draws the chat's notification from its lines; [alert] sounds it for a new message.
    private fun render(
        chatId: ChatId,
        alert: Boolean,
    ) {
        val last = posted[chatId] ?: return
        val history = lines[chatId]?.takeIf { it.isNotEmpty() } ?: return
        val chat = last.chat
        val decision = last.decision
        val title = chat.nameOverride ?: chat.title
        val newest = history.last()
        val style =
            NotificationCompat.MessagingStyle(me()).setGroupConversation(chat.kind == ChatKind.GROUP).also { s ->
                if (chat.kind == ChatKind.GROUP) s.conversationTitle = title
                history.forEach { s.addMessage(it.styled()) }
            }
        val notification =
            NotificationCompat
                .Builder(context, decision.channelId)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setStyle(style)
                .setContentTitle(title)
                .setContentText(newest.text)
                .setShortcutId(chat.id.value)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setWhen(newest.at)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!alert)
                .setSilent(decision.silent)
                .setGroup(GROUP)
                .setContentIntent(openChat(chat.id))
                .addAction(replyAction(chat.id))
                .addAction(markReadAction(chat.id))
                .apply {
                    decision.keyword?.let { setSubText(context.getString(R.string.notification_keyword, it)) }
                    // A one-time code gets its own button (UI_DESIGN.md 10.6).
                    last.code?.let { addAction(copyCodeAction(chat.id, it)) }
                }.build()
        show(chat.id.value, MESSAGE_ID, notification)
        show(SUMMARY_TAG, SUMMARY_ID, summary(decision.channelId))
    }

    // The router checks first; this is the check lint can see, and the user may have revoked it since.
    private fun show(
        tag: String,
        id: Int,
        notification: android.app.Notification,
    ) {
        val allowed =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        try {
            manager.notify(tag, id, notification)
        } catch (_: SecurityException) {
            // Revoked between the check and the call; the message is still in the inbox.
        }
    }

    /** "Sent late to <chat>": a scheduled message that missed its time but went (UI_DESIGN.md 10.13). */
    fun postLate(
        channelId: String,
        chat: Chat,
        message: Message,
    ) {
        val title = context.getString(R.string.notification_sent_late, chat.nameOverride ?: chat.title)
        val notification =
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setContentTitle(title)
                .setContentText(message.body ?: lineFor(message))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(openChat(chat.id))
                .build()
        show(chat.id.value, LATE_ID, notification)
    }

    /**
     * Takes the chat's notification down and forgets its lines. The group line goes with the
     * last chat's notification, judged by what is really in the shade, not by memory (owner,
     * Gate G3: a line stayed after the message was read).
     */
    @Synchronized
    fun clear(chatId: ChatId) {
        lines.remove(chatId)
        posted.remove(chatId)
        manager.cancel(chatId.value, MESSAGE_ID)
        val others =
            runCatching { manager.activeNotifications }
                .getOrDefault(emptyList())
                .any { it.id == MESSAGE_ID && it.tag != chatId.value }
        if (!others) manager.cancel(SUMMARY_TAG, SUMMARY_ID)
    }

    // Groups every chat's notification under one line when there are several.
    private fun summary(channelId: String) =
        NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.notification_summary))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .build()

    private fun person(
        name: String,
        key: String,
    ) = Person
        .Builder()
        .setName(name)
        .setKey(key)
        .build()

    private fun me() =
        Person
            .Builder()
            .setName(context.getString(R.string.notification_you))
            .setKey(ME)
            .build()

    // A long-lived conversation shortcut: Android's per-conversation controls and priority need one.
    private fun publishShortcut(
        chat: Chat,
        title: String,
        who: Person,
    ) {
        val shortcut =
            ShortcutInfoCompat
                .Builder(context, chat.id.value)
                .setShortLabel(title.ifBlank { context.getString(R.string.channel_messages) })
                .setLongLived(true)
                .setIsConversation()
                .setPerson(who)
                .setIntent(launch(chat.id).setAction(Intent.ACTION_VIEW))
                .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }

    private fun launch(chatId: ChatId): Intent =
        (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .putExtra(NotificationTaps.EXTRA_CHAT, chatId.value)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun openChat(chatId: ChatId): PendingIntent =
        PendingIntent.getActivity(
            context,
            chatId.value.hashCode(),
            launch(chatId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun replyAction(chatId: ChatId): NotificationCompat.Action {
        val label = context.getString(R.string.notification_reply)
        val input = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel(label).build()
        val intent =
            PendingIntent.getBroadcast(
                context,
                chatId.value.hashCode(),
                NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_REPLY, chatId),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Action
            .Builder(R.drawable.ic_stat_message, label, intent)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun markReadAction(chatId: ChatId): NotificationCompat.Action {
        val intent =
            PendingIntent.getBroadcast(
                context,
                chatId.value.hashCode() + 1,
                NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_MARK_READ, chatId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Action
            .Builder(R.drawable.ic_stat_message, context.getString(R.string.notification_mark_read), intent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun copyCodeAction(
        chatId: ChatId,
        code: String,
    ): NotificationCompat.Action {
        val intent =
            PendingIntent.getBroadcast(
                context,
                chatId.value.hashCode() + 2,
                NotificationActionReceiver
                    .intent(context, NotificationActionReceiver.ACTION_COPY_CODE, chatId)
                    .putExtra(NotificationActionReceiver.EXTRA_CODE, code),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Action
            .Builder(R.drawable.ic_stat_message, context.getString(R.string.notification_copy_code), intent)
            .setShowsUserInterface(false)
            .build()
    }

    /** The line a message makes: its text, or what kind of thing was sent (owner, Gate G2). */
    private fun lineFor(message: Message): String {
        val body = message.body?.takeIf { it.isNotBlank() }
        val media = mediaLabel(message.kind)?.let(context::getString)
        return when {
            body != null && media != null -> "$media: $body"
            body != null -> body
            media != null -> media
            else -> context.getString(R.string.notification_new_message)
        }
    }

    private fun mediaLabel(kind: MessageKind): Int? =
        when (kind) {
            MessageKind.IMAGE -> R.string.notification_sent_picture
            MessageKind.VIDEO -> R.string.notification_sent_video
            MessageKind.GIF -> R.string.notification_sent_gif
            MessageKind.STICKER -> R.string.notification_sent_sticker
            MessageKind.VOICE -> R.string.notification_sent_voice
            MessageKind.FILE -> R.string.notification_sent_file
            MessageKind.LOCATION -> R.string.notification_sent_location
            MessageKind.CONTACT -> R.string.notification_sent_contact
            MessageKind.TEXT, MessageKind.DELETED -> null
        }

    companion object {
        const val MESSAGE_ID = 100
        private const val SUMMARY_ID = 101
        private const val LATE_ID = 102
        private const val SUMMARY_TAG = "summary"
        private const val GROUP = "org.pingme.messages"
        private const val MAX_LINES = 8
        private const val ME = "me"
        private const val SYSTEM_UI = "com.android.systemui"
    }
}
