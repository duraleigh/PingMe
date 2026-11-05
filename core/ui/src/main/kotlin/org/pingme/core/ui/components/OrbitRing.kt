// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/**
 * The unread mark (owner, 2026-10-04): a thin ring in the theme's accent colour orbiting
 * the avatar, in the avatar's own shape, a little larger than the avatar with a gap
 * between them. The ring is drawn outside the avatar's bounds, so the avatar keeps its
 * size and the row keeps its layout. A muted chat's ring is the outline colour. [halo] is a
 * soft wider band of the same colour under the ring, read while drawing so it can pulse
 * without the avatar being laid out again (pinned chats, owner 2026-11-02).
 */
@Composable
fun OrbitRing(
    shape: RoundedPolygon,
    modifier: Modifier = Modifier,
    gap: Dp = 3.dp,
    stroke: Dp = 2.dp,
    /** False draws nothing, so one composable serves ringed and plain avatars alike. */
    shown: Boolean = true,
    colour: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    halo: () -> Float = { 0f },
    content: @Composable () -> Unit,
) {
    val outline = shape.toShape()
    Box(
        modifier.drawBehind {
            if (!shown) return@drawBehind
            val grow = (gap + stroke).toPx()
            val ring = Size(size.width + grow * 2, size.height + grow * 2)
            translate(-grow, -grow) {
                val path = outline.createOutline(ring, layoutDirection, this)
                val glow = halo()
                if (glow > 0f) drawOutline(path, colour.copy(alpha = glow), style = Stroke(stroke.toPx() * HALO_WIDTH))
                drawOutline(path, colour, style = Stroke(stroke.toPx()))
            }
        },
    ) {
        content()
    }
}

private const val HALO_WIDTH = 3.2f
