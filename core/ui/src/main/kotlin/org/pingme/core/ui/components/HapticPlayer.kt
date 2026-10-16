// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.pingme.core.ui.theme.Haptics
import org.pingme.core.ui.theme.PingMeTheme

/**
 * The phone's vibrator, driven directly (UI_DESIGN.md 4.5: Off, Light, Strong). The
 * framework's touch feedback was never felt on the owner's phone (2026-10-05: "none of
 * the haptics settings do anything"), so PingMe plays its own effects: a tick at a swipe
 * threshold or a hold, a bump when a reaction lands or a recording starts, a double tick
 * for a cancel. Light and Strong pick different effects; Off plays nothing.
 */
class HapticPlayer(
    private val vibrator: Vibrator?,
    private val strength: Haptics,
) {
    /** Crossing a threshold, a long press, a lock. */
    fun tick() = play(if (strength == Haptics.STRONG) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)

    /** A reaction landing, a recording starting. */
    fun bump() =
        play(
            if (strength ==
                Haptics.STRONG
            ) {
                VibrationEffect.EFFECT_HEAVY_CLICK
            } else {
                VibrationEffect.EFFECT_CLICK
            },
        )

    /** A cancel: two quick ticks. */
    fun reject() {
        val v = vibrator ?: return
        if (strength == Haptics.OFF || !v.hasVibrator()) return
        val on = if (strength == Haptics.STRONG) REJECT_STRONG_MS else REJECT_LIGHT_MS
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, on, REJECT_GAP_MS, on), -1))
        }
    }

    private fun play(effect: Int) {
        val v = vibrator ?: return
        if (strength == Haptics.OFF || !v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    companion object {
        const val REJECT_LIGHT_MS = 40L
        const val REJECT_STRONG_MS = 70L
        const val REJECT_GAP_MS = 60L

        fun vibratorOf(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
    }
}

/** The haptics player for the current Appearance setting. */
@Composable
fun rememberHaptic(): HapticPlayer {
    val context = LocalContext.current
    val strength = PingMeTheme.appearance.haptics
    return remember(strength) { HapticPlayer(HapticPlayer.vibratorOf(context), strength) }
}
