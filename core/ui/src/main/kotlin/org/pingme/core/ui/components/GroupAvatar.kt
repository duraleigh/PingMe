// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import org.pingme.core.ui.theme.PingMeTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * A group chat's avatar is made of its members' avatars, not counting you (owner,
 * 2026-10-03): two side by side, three in a triangle, four in a square, and so on round
 * to nine in a nonagon; ten or more become a multicoloured asterisk with one arm per
 * member. With nobody known, the group's own initials tile.
 */
@Composable
fun GroupAvatar(
    faces: List<Face>,
    title: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: RoundedPolygon = PingMeTheme.shapes.avatar,
) {
    when {
        faces.isEmpty() -> Avatar(title, modifier, size, shape)
        faces.size == 1 -> Avatar(faces.single().name, modifier, size, shape, faces.single().photo)
        faces.size >= ASTERISK_FROM -> Asterisk(faces, size, shape, modifier)
        else -> Ring(faces, size, shape, modifier)
    }
}

// Members on the corners of a regular polygon, each a small avatar; two sit side by side.
@Composable
private fun Ring(
    faces: List<Face>,
    size: Dp,
    shape: RoundedPolygon,
    modifier: Modifier = Modifier,
) {
    val count = faces.size
    val small = size * smallShare(count)
    val radius = (size - small) / 2
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        faces.forEachIndexed { index, face ->
            // Start at the top (three make an upright triangle); two land left and right.
            val angle = if (count == PAIR) Math.PI * index else -Math.PI / 2 + 2 * Math.PI * index / count
            val x = radius * cos(angle).toFloat()
            val y = radius * sin(angle).toFloat()
            Avatar(face.name, Modifier.offset(x, y), small, shape, face.photo)
        }
    }
}

// Ten or more: an asterisk, one arm per member in that member's own colour.
@Composable
private fun Asterisk(
    faces: List<Face>,
    size: Dp,
    shape: RoundedPolygon,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.run { red + green + blue } < 1.5f
    val colours = remember(faces, dark) { faces.map { initialsColors(it.name, dark).first } }
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(
        modifier
            .size(size)
            .clip(shape.toShape())
            .background(background),
    ) {
        val centre = Offset(this.size.width / 2, this.size.height / 2)
        val reach = this.size.minDimension * ARM_REACH
        val stroke = (this.size.minDimension * ARM_WIDTH).coerceAtLeast(2f)
        colours.forEachIndexed { index, colour ->
            val angle = -Math.PI / 2 + 2 * Math.PI * index / colours.size
            val end = Offset(centre.x + reach * cos(angle).toFloat(), centre.y + reach * sin(angle).toFloat())
            drawLine(colour, centre, end, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** How big each member's avatar is, as a share of the whole: fewer members, bigger faces. */
private fun smallShare(count: Int): Float =
    when {
        count <= PAIR -> HALF
        count <= SQUARE -> NEAR_HALF
        count <= HEXAGON -> LARGE_RING
        else -> SMALL_RING
    }

private const val PAIR = 2
private const val SQUARE = 4
private const val HEXAGON = 6
private const val HALF = 0.5f
private const val NEAR_HALF = 0.46f
private const val LARGE_RING = 0.38f
private const val SMALL_RING = 0.32f

private const val ASTERISK_FROM = 10
private const val ARM_REACH = 0.42f
private const val ARM_WIDTH = 0.07f
