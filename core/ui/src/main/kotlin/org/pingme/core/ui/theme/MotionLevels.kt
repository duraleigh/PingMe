// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MotionScheme

/**
 * How screens and panes come and go at each motion level (UI_DESIGN.md 4.5): Off is instant,
 * Subtle a short fade, Full a quarter-width slide on the theme's expressive springs, and Extra
 * a full-width bouncy slide that grows into place. [fromEnd] enters from the end side (going
 * forward); [toEnd] leaves towards it (going back).
 */
object ScreenMotion {
    fun enter(
        level: MotionIntensity,
        scheme: MotionScheme,
        fromEnd: Boolean = true,
    ): EnterTransition {
        val side = if (fromEnd) 1 else -1
        return when (level) {
            MotionIntensity.OFF -> {
                EnterTransition.None
            }

            MotionIntensity.SUBTLE -> {
                fadeIn(tween(SUBTLE_IN_MS))
            }

            MotionIntensity.FULL -> {
                slideInHorizontally(scheme.defaultSpatialSpec()) { side * it / FULL_SLIDE } +
                    fadeIn(scheme.defaultEffectsSpec())
            }

            MotionIntensity.EXTRA -> {
                slideInHorizontally(bouncy()) { side * it } +
                    scaleIn(bouncy(), initialScale = EXTRA_SCALE) +
                    fadeIn(scheme.fastEffectsSpec())
            }
        }
    }

    fun exit(
        level: MotionIntensity,
        scheme: MotionScheme,
        toEnd: Boolean = false,
    ): ExitTransition {
        val side = if (toEnd) 1 else -1
        return when (level) {
            MotionIntensity.OFF -> {
                ExitTransition.None
            }

            MotionIntensity.SUBTLE -> {
                fadeOut(tween(SUBTLE_OUT_MS))
            }

            MotionIntensity.FULL -> {
                slideOutHorizontally(scheme.defaultSpatialSpec()) { side * it / FULL_SLIDE_OUT } +
                    fadeOut(scheme.defaultEffectsSpec())
            }

            MotionIntensity.EXTRA -> {
                slideOutHorizontally(scheme.fastSpatialSpec()) { side * it / EXTRA_SLIDE_OUT } +
                    scaleOut(scheme.fastSpatialSpec(), targetScale = EXTRA_SCALE) +
                    fadeOut(scheme.fastEffectsSpec())
            }
        }
    }

    private fun <T> bouncy() =
        spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

    private const val SUBTLE_IN_MS = 180
    private const val SUBTLE_OUT_MS = 120
    private const val FULL_SLIDE = 4
    private const val FULL_SLIDE_OUT = 8
    private const val EXTRA_SLIDE_OUT = 3
    private const val EXTRA_SCALE = 0.9f
}

/**
 * Where a new message's bubble starts at each motion level (UI_DESIGN.md 4.5), springing to
 * its place: Subtle only fades in, Full also rises and grows a little, Extra pops from half
 * size with a bounce. Off has none: the bubble is simply there.
 */
data class BubbleEntrance(
    val fromScale: Float,
    val riseDp: Float,
    val dampingRatio: Float,
    val stiffness: Float,
) {
    companion object {
        fun at(level: MotionIntensity): BubbleEntrance? =
            when (level) {
                MotionIntensity.OFF -> {
                    null
                }

                MotionIntensity.SUBTLE -> {
                    BubbleEntrance(1f, 0f, Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
                }

                MotionIntensity.FULL -> {
                    BubbleEntrance(FULL_SCALE, FULL_RISE, Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)
                }

                MotionIntensity.EXTRA -> {
                    BubbleEntrance(EXTRA_SCALE, EXTRA_RISE, Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
                }
            }

        private const val FULL_SCALE = 0.9f
        private const val FULL_RISE = 16f
        private const val EXTRA_SCALE = 0.5f
        private const val EXTRA_RISE = 40f
    }
}
