// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A message bubble's outline. The last bubble of a run carries a tail at its bottom
 * corner on the sender's side (UI_DESIGN.md 3.2); others keep a tighter corner there so a
 * run of bubbles reads as one group.
 */
class BubbleShape(
    private val corner: Dp,
    private val outgoing: Boolean,
    private val tail: Boolean,
    private val groupedBelow: Boolean,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { corner.toPx() }.coerceAtMost(size.minDimension / 2)
        val tight = with(density) { GROUPED_CORNER.toPx() }.coerceAtMost(r)
        val tailWidth = with(density) { TAIL_WIDTH.toPx() }
        val tailHeight = with(density) { TAIL_HEIGHT.toPx() }.coerceAtMost(size.height / 2)
        // The side the sender is on, taking right-to-left layouts into account.
        val onRight = outgoing == (layoutDirection == LayoutDirection.Ltr)
        val body = body(size, r, tight, tailWidth, onRight)
        val path = Path().apply { addRoundRect(body) }
        if (!tail) return Outline.Generic(path)
        val tailPath = tailPath(body, size, onRight, tight, tailHeight)
        return Outline.Generic(Path.combine(PathOperation.Union, path, tailPath))
    }

    /** The bubble without its tail; the sender's bottom corner is tight when a tail or the next bubble sits there. */
    private fun body(
        size: Size,
        r: Float,
        tight: Float,
        tailWidth: Float,
        onRight: Boolean,
    ): RoundRect {
        val senderBottom = if (tail || groupedBelow) tight else r
        return RoundRect(
            left = if (onRight || !tail) 0f else tailWidth,
            top = 0f,
            right = if (onRight && tail) size.width - tailWidth else size.width,
            bottom = size.height,
            topLeftCornerRadius = CornerRadius(r),
            topRightCornerRadius = CornerRadius(r),
            bottomRightCornerRadius = CornerRadius(if (onRight) senderBottom else r),
            bottomLeftCornerRadius = CornerRadius(if (onRight) r else senderBottom),
        )
    }

    /** The small curved tail at the sender's bottom corner. */
    private fun tailPath(
        body: RoundRect,
        size: Size,
        onRight: Boolean,
        tight: Float,
        tailHeight: Float,
    ) = Path().apply {
        if (onRight) {
            moveTo(body.right - tight, size.height - tailHeight)
            quadraticTo(body.right, size.height - tailHeight / 2, size.width, size.height)
            lineTo(body.right - tight * 2, size.height)
        } else {
            moveTo(body.left + tight, size.height - tailHeight)
            quadraticTo(body.left, size.height - tailHeight / 2, 0f, size.height)
            lineTo(body.left + tight * 2, size.height)
        }
        close()
    }

    companion object {
        val TAIL_WIDTH = 6.dp
        private val TAIL_HEIGHT = 14.dp
        private val GROUPED_CORNER = 6.dp

        /** The pill style: fully round ends, no tail. */
        val Pill = RoundedCornerShape(percent = 50)
    }
}
