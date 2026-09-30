// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.model.Chat
import org.pingme.core.model.Message
import org.pingme.core.store.ChatRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/** What to do with one incoming message. */
sealed interface NotificationDecision {
    data object Drop : NotificationDecision

    data class Notify(
        val channelId: String,
    ) : NotificationDecision
}

/**
 * Decides whether and how a new message notifies (BUILD_PLAN.md P1.4 skeleton; the full
 * precedence of keyword, per-chat, folder, and account channels, conversation styling, OTP
 * detection, and reply actions arrive in P4.1). Today: outgoing, muted, and low priority
 * messages stay quiet, obscured chats show "New message" only, and everything else posts
 * a plain notification on the default channel.
 */
@Singleton
class NotificationRouter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val chats: ChatRepository,
        private val clock: Clock,
    ) {
        suspend fun onEvent(event: ConnectorEvent) {
            if (event !is ConnectorEvent.NewMessage) return
            val message = event.message.message
            val chat = chats.get(message.chatId) ?: return
            val decision = decide(chat, message, clock.now())
            if (decision is NotificationDecision.Notify) post(decision, chat, message)
        }

        private fun post(
            decision: NotificationDecision.Notify,
            chat: Chat,
            message: Message,
        ) {
            val allowed =
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!allowed) return
            ensureDefaultChannel()
            val text =
                if (chat.isObscured) {
                    context.getString(
                        R.string.notification_new_message,
                    )
                } else {
                    message.body.orEmpty()
                }
            val notification =
                NotificationCompat
                    .Builder(context, decision.channelId)
                    .setSmallIcon(R.drawable.ic_stat_message)
                    .setContentTitle(chat.nameOverride ?: chat.title)
                    .setContentText(text)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .build()
            NotificationManagerCompat.from(context).notify(chat.id.value, 0, notification)
        }

        private fun ensureDefaultChannel() {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(DEFAULT_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        DEFAULT_CHANNEL,
                        context.getString(R.string.channel_messages),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
        }

        companion object {
            const val DEFAULT_CHANNEL = "default"

            fun decide(
                chat: Chat,
                message: Message,
                now: Instant,
            ): NotificationDecision {
                val muted = chat.isMuted && chat.muteUntil.let { it == null || it > now }
                return when {
                    message.isOutgoing -> NotificationDecision.Drop
                    muted || chat.isLowPriority -> NotificationDecision.Drop
                    else -> NotificationDecision.Notify(DEFAULT_CHANNEL)
                }
            }
        }
    }
