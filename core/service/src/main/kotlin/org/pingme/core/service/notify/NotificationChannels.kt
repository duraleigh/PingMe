// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import org.pingme.core.model.ChatOverrides
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.VibrationPattern

/**
 * Notification channels, one per sound (UI_DESIGN.md 6.1): Android cannot change a channel's
 * sound once made, so a channel id ends in a version, and a new version replaces the old
 * channel. Ids: `default`, `chat_<id>_<n>`, `keyword_<id>_<n>`, `folder_<name>_<n>`,
 * `account_<id>_<n>` (BUILD_PLAN.md P4.1).
 */
internal class NotificationChannels(
    private val context: Context,
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /** Makes [id] exist with these settings, removing older versions of the same channel. Returns [id]. */
    fun ensure(
        id: String,
        name: String,
        mode: NotificationMode = NotificationMode.NORMAL,
        soundUri: String? = null,
        vibration: VibrationPattern? = null,
    ): String {
        if (manager.getNotificationChannel(id) != null) return id
        val importance =
            if (mode == NotificationMode.SILENT) {
                NotificationManager.IMPORTANCE_LOW
            } else {
                NotificationManager.IMPORTANCE_HIGH
            }
        val channel =
            NotificationChannel(id, name, importance).apply {
                when (soundUri) {
                    ChatOverrides.SILENT -> setSound(null, null)
                    null -> setSound(Settings.System.DEFAULT_NOTIFICATION_URI, NOTIFICATION_AUDIO)
                    else -> setSound(Uri.parse(soundUri), NOTIFICATION_AUDIO)
                }
                when (vibration) {
                    null -> {
                        enableVibration(true)
                    }

                    VibrationPattern.OFF -> {
                        enableVibration(false)
                    }

                    else -> {
                        enableVibration(true)
                        vibrationPattern = vibration.timings
                    }
                }
            }
        removeOlderVersions(id)
        manager.createNotificationChannel(channel)
        return id
    }

    // "chat_42_3" replaces "chat_42_2": same channel to the user, new sound underneath.
    private fun removeOlderVersions(id: String) {
        val stem = id.substringBeforeLast('_')
        if (stem == id || stem == DEFAULT) return
        manager.notificationChannels
            .filter { it.id != id && it.id.substringBeforeLast('_') == stem }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    companion object {
        const val DEFAULT = "default"

        private val NOTIFICATION_AUDIO =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
    }
}
