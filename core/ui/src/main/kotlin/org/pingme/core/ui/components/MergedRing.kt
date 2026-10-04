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
 * A merged chat's mark (owner, 2026-10-03): a thin ring in the theme's accent colour
 * orbiting the avatar, in the avatar's own shape, a little larger than the avatar with a
 * gap between them. The ring is drawn outside the avatar's bounds, so the avatar keeps
 * its size and the row keeps its layout.
 */
@Composable
fun MergedRing(
    shape: RoundedPolygon,
    modifier: Modifier = Modifier,
    gap: Dp = 3.dp,
    stroke: Dp = 2.dp,
    /** False draws nothing, so one composable serves ringed and plain avatars alike. */
    shown: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colour = MaterialTheme.colorScheme.primary
    val outline = shape.toShape()
    Box(
        modifier.drawBehind {
            if (!shown) return@drawBehind
            val grow = (gap + stroke).toPx()
            val ring = Size(size.width + grow * 2, size.height + grow * 2)
            translate(-grow, -grow) {
                drawOutline(outline.createOutline(ring, layoutDirection, this), colour, style = Stroke(stroke.toPx()))
            }
        },
    ) {
        content()
    }
}
