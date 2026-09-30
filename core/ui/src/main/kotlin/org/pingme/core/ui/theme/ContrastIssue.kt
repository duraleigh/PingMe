// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Transport
import org.pingme.core.ui.components.styleBubble
import org.pingme.core.ui.components.textContrast

/** A colour combination below the 4.5:1 minimum (UI_DESIGN.md 7). */
data class ContrastIssue(
    val what: String,
    val ratio: Double,
)

/**
 * Checks every bubble and the wallpaper against the 4.5:1 contrast minimum, so the
 * Appearance studio can warn when a combination fails (UI_DESIGN.md 7, BUILD_PLAN.md P2.2).
 */
fun contrastIssues(
    appearance: Appearance,
    scheme: ColorScheme,
    palette: NetworkPalette,
): List<ContrastIssue> {
    val checks =
        buildList {
            NETWORK_LABELS.forEach { (network, transport, label) ->
                val styled =
                    styleBubble(
                        palette.outgoing(network, transport),
                        appearance.bubbleStyle,
                        palette.accent(network),
                        scheme.surface,
                        scheme.onSurface,
                        outgoing = true,
                    )
                add("$label bubbles" to styled.textContrast())
            }
            add("Incoming bubbles" to contrastRatio(palette.incoming.container, palette.incoming.content))
            wallpaperColors(appearance.wallpaper)?.let { colors ->
                add("Dates and notices on the wallpaper" to colors.minOf { contrastRatio(it, scheme.onSurface) })
            }
        }
    return checks.filter { (_, ratio) -> ratio < MIN_TEXT_CONTRAST }.map { (what, ratio) -> ContrastIssue(what, ratio) }
}

private fun wallpaperColors(wallpaper: ChatWallpaper): List<Color>? =
    when (wallpaper) {
        is ChatWallpaper.Colour -> listOf(Color(wallpaper.argb))

        is ChatWallpaper.Gradient -> listOf(Color(wallpaper.from), Color(wallpaper.to))

        // A picture has no single colour; dates sit on a tonal chip over it instead.
        is ChatWallpaper.Image, ChatWallpaper.None -> null
    }

private val NETWORK_LABELS =
    listOf(
        Triple(NetworkId.GMESSAGES, Transport.RCS, "RCS"),
        Triple(NetworkId.GMESSAGES, Transport.SMS, "SMS"),
        Triple(NetworkId.WHATSAPP, Transport.NETWORK, "WhatsApp"),
        Triple(NetworkId.GVOICE, Transport.NETWORK, "Google Voice"),
        Triple(NetworkId.SIGNAL, Transport.NETWORK, "Signal"),
        Triple(NetworkId.TELEGRAM, Transport.NETWORK, "Telegram"),
        Triple(NetworkId.MESSENGER, Transport.NETWORK, "Messenger"),
        Triple(NetworkId.INSTAGRAM, Transport.NETWORK, "Instagram"),
        Triple(NetworkId.SMS, Transport.SMS, "Native SMS"),
    )
