// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import org.pingme.core.ui.theme.BubbleColors
import org.pingme.core.ui.theme.BubbleStyle
import org.pingme.core.ui.theme.contrastRatio

/** A bubble's final look after the user's bubble style (UI_DESIGN.md 4.2) is applied. */
@Immutable
data class StyledBubble(
    val background: Brush,
    /** The colours text sits on, for contrast checks: one for solid fills, two ends for a gradient. */
    val backgroundStops: List<Color>,
    val content: Color,
    val border: Color?,
    val pill: Boolean,
)

/**
 * Applies a bubble style to a bubble's network colours. [accent] is the network's own
 * colour, used by Filled and Outlined. Incoming bubbles keep their tonal surface in every
 * style (UI_DESIGN.md 3.2); only the pill shape applies to them.
 */
fun styleBubble(
    colors: BubbleColors,
    style: BubbleStyle,
    accent: Color,
    surface: Color,
    onSurface: Color,
    outgoing: Boolean,
): StyledBubble {
    fun solid(
        fill: Color,
        text: Color,
        border: Color? = colors.outline,
        pill: Boolean = false,
    ) = StyledBubble(SolidColor(fill), listOf(fill), text, border, pill)
    if (!outgoing) return solid(colors.container, colors.content, pill = style == BubbleStyle.PILL)
    return when (style) {
        BubbleStyle.TONAL -> {
            solid(colors.container, colors.content)
        }

        BubbleStyle.PILL -> {
            solid(colors.container, colors.content, pill = true)
        }

        BubbleStyle.OUTLINED -> {
            solid(surface, onSurface, border = accent)
        }

        BubbleStyle.FILLED -> {
            solid(accent, readableOn(accent))
        }

        BubbleStyle.GRADIENT -> {
            val start = colors.container
            val end =
                Hct
                    .fromInt(
                        start.toArgb(),
                    ).let { Color(it.withHue((it.hue + GRADIENT_HUE_SHIFT) % FULL_TURN).toInt()) }
            StyledBubble(
                Brush.linearGradient(listOf(start, end)),
                listOf(start, end),
                colors.content,
                colors.outline,
                pill = false,
            )
        }
    }
}

/** White or near-black, whichever reads better on [background]. */
fun readableOn(background: Color): Color =
    if (contrastRatio(background, Color.White) >= contrastRatio(background, NEAR_BLACK)) Color.White else NEAR_BLACK

/** The weakest text contrast anywhere on the bubble. */
fun StyledBubble.textContrast(): Double = backgroundStops.minOf { contrastRatio(it, content) }

private val NEAR_BLACK = Color(0xFF1B1B1F)
private const val GRADIENT_HUE_SHIFT = 40.0
private const val FULL_TURN = 360.0
