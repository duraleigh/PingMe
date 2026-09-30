// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle

/**
 * A shape family (UI_DESIGN.md 4.2): the Material component shapes, the polygon avatars
 * are drawn in, the polygons the pinned tiles cycle through, and the default bubble corner.
 * Polygons stay [RoundedPolygon]s so they can morph in animations.
 */
@Immutable
class PingMeShapes(
    val family: ShapeFamily,
    val components: Shapes,
    val avatar: RoundedPolygon,
    val pinnedTiles: List<RoundedPolygon>,
    val bubbleCorner: Dp,
) {
    /** The polygon for the pinned tile at [index]; Expressive varies them, the others repeat one. */
    fun pinnedTile(index: Int): RoundedPolygon = pinnedTiles[index % pinnedTiles.size]

    companion object {
        fun of(family: ShapeFamily): PingMeShapes =
            when (family) {
                ShapeFamily.ROUND -> {
                    PingMeShapes(
                        family,
                        shapes(extraSmall = 12, small = 16, medium = 24, large = 28, extraLarge = 32),
                        MaterialShapes.Circle,
                        listOf(MaterialShapes.Circle),
                        22.dp,
                    )
                }

                ShapeFamily.SOFT -> {
                    PingMeShapes(
                        family,
                        shapes(extraSmall = 6, small = 10, medium = 14, large = 18, extraLarge = 28),
                        MaterialShapes.Square,
                        listOf(MaterialShapes.Square),
                        16.dp,
                    )
                }

                ShapeFamily.SHARP -> {
                    val square = RoundedPolygon.rectangle(rounding = CornerRounding(SHARP_ROUNDING))
                    PingMeShapes(
                        family,
                        shapes(extraSmall = 2, small = 4, medium = 6, large = 8, extraLarge = 12),
                        square,
                        listOf(square),
                        6.dp,
                    )
                }

                ShapeFamily.EXPRESSIVE -> {
                    PingMeShapes(
                        family = family,
                        components = shapes(extraSmall = 8, small = 12, medium = 20, large = 28, extraLarge = 36),
                        avatar = MaterialShapes.Cookie9Sided,
                        pinnedTiles =
                            listOf(
                                MaterialShapes.Cookie9Sided,
                                MaterialShapes.Clover4Leaf,
                                MaterialShapes.Sunny,
                                MaterialShapes.SoftBurst,
                                MaterialShapes.Cookie6Sided,
                                MaterialShapes.Flower,
                            ),
                        bubbleCorner = 22.dp,
                    )
                }
            }

        private fun shapes(
            extraSmall: Int,
            small: Int,
            medium: Int,
            large: Int,
            extraLarge: Int,
        ) = Shapes(
            extraSmall = RoundedCornerShape(extraSmall.dp),
            small = RoundedCornerShape(small.dp),
            medium = RoundedCornerShape(medium.dp),
            large = RoundedCornerShape(large.dp),
            extraLarge = RoundedCornerShape(extraLarge.dp),
        )

        /** Corner rounding of the Sharp avatar square, as a fraction of its size. */
        private const val SHARP_ROUNDING = 0.12f
    }
}
