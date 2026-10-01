// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable

/**
 * One chat's own settings from Chat details (UI_DESIGN.md 3.4): its notification sound and
 * vibration (6.1), its look, and its quick-reaction set. Null fields follow the app-wide
 * setting.
 */
@Serializable
data class ChatOverrides(
    val chatId: ChatId,
    /** A ringtone or audio file address; null for the default sound, [SILENT] for none. */
    val soundUri: String? = null,
    val vibration: VibrationPattern? = null,
    /**
     * Android cannot change a notification channel's sound, so each new sound gets a new
     * channel; this counts them (UI_DESIGN.md 6.1).
     */
    val channelVersion: Int = 0,
    /** This chat's colour, bubble style, wallpaper, and text size, as the app stores them. */
    val lookJson: String? = null,
    val quickReactions: List<String>? = null,
) {
    val isCustomNotification: Boolean get() = soundUri != null || vibration != null

    companion object {
        const val SILENT = "silent"
    }
}

/** Vibration choices for a chat's notifications (UI_DESIGN.md 6.1). */
@Serializable
enum class VibrationPattern(
    /** Off, on, off, on... in milliseconds, as Android's vibrator takes it. */
    val timings: LongArray,
) {
    OFF(longArrayOf()),
    SHORT(longArrayOf(0, 150)),
    LONG(longArrayOf(0, 600)),
    DOUBLE(longArrayOf(0, 150, 120, 150)),
    HEARTBEAT(longArrayOf(0, 120, 80, 220)),
    RAPID(longArrayOf(0, 80, 60, 80, 60, 80)),
}
