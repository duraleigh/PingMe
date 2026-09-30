// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.MotionScheme

/**
 * The motion intensity actually used: the user's choice, unless the system "remove
 * animations" setting is on, which always wins (UI_DESIGN.md 4.5, 7).
 */
fun effectiveMotion(
    context: Context,
    chosen: MotionIntensity,
): MotionIntensity {
    val animatorScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return if (animatorScale == 0f) MotionIntensity.OFF else chosen
}

/** Expressive springs, or the standard scheme when animation intensity is Off (BUILD_PLAN.md P2.1). */
fun motionSchemeFor(intensity: MotionIntensity): MotionScheme =
    if (intensity == MotionIntensity.OFF) MotionScheme.standard() else MotionScheme.expressive()
