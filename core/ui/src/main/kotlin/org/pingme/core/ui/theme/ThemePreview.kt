// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Transport

/**
 * A sample of the theme: a heading, the avatar and pinned-tile shapes, and an outgoing
 * bubble for every network, including the SMS fallback. The Appearance studio (P2.2)
 * builds its live preview from the same pieces.
 */
@Composable
fun ThemeSample(modifier: Modifier = Modifier) {
    Surface(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PingMe", style = PingMeTheme.headline)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(PINNED_SAMPLES) { index ->
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(PingMeTheme.shapes.pinnedTile(index).toShape())
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }
            SAMPLE_BUBBLES.forEach { (network, transport, label) ->
                val colors = PingMeTheme.networkColors.outgoing(network, transport)
                val shape = RoundedCornerShape(PingMeTheme.shapes.bubbleCorner)
                Box(
                    Modifier
                        .align(Alignment.End)
                        .clip(shape)
                        .background(colors.container)
                        .let { if (colors.outline != null) it.border(1.dp, colors.outline, shape) else it }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(label, color = colors.content, style = PingMeTheme.messageText)
                }
            }
        }
    }
}

private const val PINNED_SAMPLES = 5

private val SAMPLE_BUBBLES =
    listOf(
        Triple(NetworkId.GMESSAGES, Transport.RCS, "RCS: Leaving at 7"),
        Triple(NetworkId.GMESSAGES, Transport.SMS, "SMS: Leaving at 7"),
        Triple(NetworkId.WHATSAPP, Transport.NETWORK, "WhatsApp"),
        Triple(NetworkId.GVOICE, Transport.NETWORK, "Google Voice"),
        Triple(NetworkId.SIGNAL, Transport.NETWORK, "Signal"),
        Triple(NetworkId.TELEGRAM, Transport.NETWORK, "Telegram"),
        Triple(NetworkId.MESSENGER, Transport.NETWORK, "Messenger"),
        Triple(NetworkId.INSTAGRAM, Transport.NETWORK, "Instagram"),
        Triple(NetworkId.SMS, Transport.SMS, "Native SMS"),
    )

@Preview(name = "Expressive, light")
@Composable
private fun ThemeSampleLightPreview() {
    PingMeTheme(Appearance(colorSource = ColorSource.Preset(ThemePreset.PINGME.name), mode = ThemeMode.LIGHT)) {
        ThemeSample()
    }
}

@Preview(name = "Round, dark, AMOLED")
@Composable
private fun ThemeSampleDarkPreview() {
    PingMeTheme(
        Appearance(
            colorSource = ColorSource.Preset(ThemePreset.OCEAN.name),
            mode = ThemeMode.DARK,
            amoled = true,
            shapeFamily = ShapeFamily.ROUND,
            uiFont = FontChoice.Bundled(BundledFont.LEXEND),
        ),
    ) { ThemeSample() }
}
