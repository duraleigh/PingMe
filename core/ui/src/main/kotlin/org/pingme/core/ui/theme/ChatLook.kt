// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.pingme.core.model.NetworkId

/**
 * One chat's own look from Chat details (UI_DESIGN.md 3.4, 4): its outgoing bubble colour,
 * bubble style, wallpaper, and text size. Each null part follows the app's appearance.
 */
@Serializable
data class ChatLook(
    /** Outgoing bubble colour as ARGB, in place of the network's colour (UI_DESIGN.md 4.1). */
    val bubbleColor: Int? = null,
    val bubbleStyle: BubbleStyle? = null,
    val wallpaper: ChatWallpaper? = null,
    val fontScale: Float? = null,
) {
    val isEmpty: Boolean get() = this == ChatLook()

    fun toJson(): String? = if (isEmpty) null else json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The look stored with a chat; nothing set when there is none or it cannot be read. */
        fun fromJson(text: String?): ChatLook =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: ChatLook()
    }
}

/** The app's appearance with [look]'s choices laid over it, for a chat on [network]. */
fun Appearance.with(
    look: ChatLook,
    network: NetworkId,
): Appearance =
    copy(
        networkColors = look.bubbleColor?.let { networkColors + (network to it) } ?: networkColors,
        bubbleStyle = look.bubbleStyle ?: bubbleStyle,
        wallpaper = look.wallpaper ?: wallpaper,
        fontScale = look.fontScale ?: fontScale,
    )
