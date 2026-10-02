// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.notify

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings

/**
 * The sound a channel would make, played on its own: with PingMe open on another screen
 * a message makes its sound and nothing lands in the shade (owner, Gate G3). Silent
 * channels, channels the user turned down, and Do not disturb all keep quiet.
 */
internal class NotificationSounds(
    private val context: Context,
) {
    fun play(channelId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        val channel = manager.getNotificationChannel(channelId)
        val uri =
            when {
                channel == null -> Settings.System.DEFAULT_NOTIFICATION_URI
                channel.importance < NotificationManager.IMPORTANCE_DEFAULT -> return
                else -> channel.sound ?: return
            }
        runCatching {
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes =
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                play()
            }
        }
    }
}
