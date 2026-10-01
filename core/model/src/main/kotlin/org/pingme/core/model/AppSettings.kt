// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable

/**
 * The app-wide settings from Settings (BUILD_PLAN.md P2.6). Appearance, reactions, and the
 * inbox bar are kept on their own; everything else is here. New fields need defaults, so
 * settings saved by an older version still read.
 */
@Serializable
data class AppSettings(
    val notifications: NotificationSettings = NotificationSettings(),
    val privacy: PrivacySettings = PrivacySettings(),
    val media: MediaSettings = MediaSettings(),
    /** "Flippy reactions" (UI_DESIGN.md 10.8); null follows the motion level. */
    val flippyReactions: Boolean? = null,
    /** Emoji that also get the screen-edge glow at Extra intensity (UI_DESIGN.md 5.4). */
    val specialEmoji: Set<String> = emptySet(),
)

/** How one network, account, or Instagram folder notifies (UI_DESIGN.md 6.1, 6.4, 6.5). */
@Serializable
data class NotificationProfile(
    val mode: NotificationMode = NotificationMode.NORMAL,
    /** A ringtone or file address; null for the default sound, [ChatOverrides.SILENT] for none. */
    val soundUri: String? = null,
    val vibration: VibrationPattern? = null,
    /** A new sound needs a new channel; this counts them (UI_DESIGN.md 6.1). */
    val channelVersion: Int = 0,
)

/** Settings > Notifications (UI_DESIGN.md 6.1, 6.4, 10.6). */
@Serializable
data class NotificationSettings(
    val networks: Map<NetworkId, NotificationProfile> = emptyMap(),
    val folders: Map<ChatFolder, NotificationProfile> = DEFAULT_FOLDERS,
    /** "Auto-copy one-time codes", off by default (UI_DESIGN.md 10.6). */
    val autoCopyCodes: Boolean = false,
    /** Each keyword rule's sound and vibration, by rule id (UI_DESIGN.md 10.9). */
    val keywords: Map<String, NotificationProfile> = emptyMap(),
) {
    fun network(id: NetworkId) = networks[id] ?: NotificationProfile()

    fun folder(folder: ChatFolder) = folders[folder] ?: DEFAULT_FOLDERS[folder] ?: NotificationProfile()

    companion object {
        /** Primary on, General silent, Requests off (UI_DESIGN.md 6.4). */
        val DEFAULT_FOLDERS =
            mapOf(
                ChatFolder.PRIMARY to NotificationProfile(NotificationMode.NORMAL),
                ChatFolder.GENERAL to NotificationProfile(NotificationMode.SILENT),
                ChatFolder.REQUESTS to NotificationProfile(NotificationMode.OFF),
            )
    }
}

/** Settings > Privacy (UI_DESIGN.md 10.3, 10.11, 10.12). */
@Serializable
data class PrivacySettings(
    val readReceipts: Boolean = true,
    val typing: Boolean = true,
    /** Per-network exceptions to [readReceipts]. */
    val readReceiptsByNetwork: Map<NetworkId, Boolean> = emptyMap(),
    /** Per-network exceptions to [typing]. */
    val typingByNetwork: Map<NetworkId, Boolean> = emptyMap(),
    val cleanLinksSent: Boolean = true,
    val cleanLinksReceived: Boolean = true,
    val linkPreviews: LinkPreviewMode = LinkPreviewMode.ALWAYS,
) {
    fun sendsReadReceipts(network: NetworkId) = readReceiptsByNetwork[network] ?: readReceipts

    fun sendsTyping(network: NetworkId) = typingByNetwork[network] ?: typing
}

/** When PingMe fetches a link's preview itself (UI_DESIGN.md 10.12). */
@Serializable
enum class LinkPreviewMode { ALWAYS, WIFI_ONLY, NEVER }

/** GIFs, voice notes, and Settings > Storage (UI_DESIGN.md 5.5, 5.6, 10.16). */
@Serializable
data class MediaSettings(
    /** Online GIF search; off leaves favourites only. */
    val gifSearch: Boolean = true,
    /** Received GIFs play by themselves; off plays them on tap (data saver). */
    val gifsAutoplay: Boolean = true,
    /** On-device voice-note transcription, off by default. */
    val transcribeVoice: Boolean = false,
    /** "Save all incoming media". */
    val saveAllMedia: Boolean = false,
    /** The folder the user chose for saved media; null keeps it in app storage. */
    val saveFolderUri: String? = null,
)
