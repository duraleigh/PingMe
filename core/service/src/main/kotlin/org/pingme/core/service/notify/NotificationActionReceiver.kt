// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pingme.core.connector.Diag
import org.pingme.core.model.ChatId
import org.pingme.core.service.ApplicationScope
import org.pingme.core.service.ChatActions
import org.pingme.core.service.MessageActions
import javax.inject.Inject

/** Reply and Mark read from a notification (UI_DESIGN.md 6.2), without opening the app. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var messages: MessageActions

    @Inject lateinit var chats: ChatActions

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val chatId = NotificationTaps.chatIn(intent) ?: return
        if (intent.action == ACTION_COPY_CODE) {
            intent.getStringExtra(EXTRA_CODE)?.let { OneTimeCodes.copy(context, it) }
            return
        }
        val reply =
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(KEY_REPLY)
                ?.toString()
                ?.trim()
        val pending = goAsync()
        // Every step goes to the diagnostic file (owner, 2026-10-05: a reply from the shade
        // took the notification down and sent nothing).
        Diag.note(TAG, "${intent.action} for ${chatId.value}: ${reply?.length ?: 0} characters")
        scope.launch {
            try {
                if (intent.action == ACTION_REPLY && !reply.isNullOrEmpty()) {
                    val sent = messages.send(chatId, reply)
                    Diag.note(TAG, "Reply to ${chatId.value} is ${sent.id.value}: ${sent.status}")
                }
                // Either way the chat is read now, which also takes the notification down.
                chats.setRead(chatId, read = true)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "notification action ${intent.action} failed", e)
                Diag.note(TAG, "${intent.action} for ${chatId.value} failed: $e")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "org.pingme.action.REPLY"
        const val ACTION_MARK_READ = "org.pingme.action.MARK_READ"
        const val ACTION_COPY_CODE = "org.pingme.action.COPY_CODE"
        const val KEY_REPLY = "reply"
        const val EXTRA_CODE = "org.pingme.code"
        private const val TAG = "PingMeNotify"

        fun intent(
            context: Context,
            action: String,
            chatId: ChatId,
        ): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(NotificationTaps.EXTRA_CHAT, chatId.value)
    }
}
