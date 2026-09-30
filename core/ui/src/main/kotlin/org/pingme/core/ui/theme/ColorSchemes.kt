// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/** The seed PingMe falls back to where wallpaper colours are unavailable (Android 10 and 11). */
val DEFAULT_SEED = Color(ThemePreset.PINGME.seed)

/**
 * Builds the colour scheme (BUILD_PLAN.md P2.1): Material You from the wallpaper, a
 * tonal scheme from a seed, a preset, or a manual palette; light or dark; AMOLED black;
 * and three contrast levels.
 */
fun resolveColorScheme(
    context: Context,
    appearance: Appearance,
    dark: Boolean,
): ColorScheme {
    val amoled = dark && appearance.amoled
    return when (val source = appearance.colorSource) {
        ColorSource.Dynamic -> {
            dynamicScheme(context, dark, amoled, appearance.contrast)
        }

        is ColorSource.Seed -> {
            seedScheme(Color(source.argb), dark, amoled, appearance.contrast)
        }

        is ColorSource.Preset -> {
            seedScheme(Color(ThemePreset.named(source.name).seed), dark, amoled, appearance.contrast)
        }

        is ColorSource.Manual -> {
            dynamicColorScheme(
                seedColor = Color(source.primary),
                isDark = dark,
                isAmoled = amoled,
                primary = Color(source.primary),
                secondary = source.secondary?.let(::Color),
                tertiary = source.tertiary?.let(::Color),
                neutral = source.neutral?.let(::Color),
                style = PaletteStyle.TonalSpot,
                contrastLevel = appearance.contrast.value,
            )
        }
    }
}

/** A tonal scheme from one colour. */
fun seedScheme(
    seed: Color,
    dark: Boolean,
    amoled: Boolean,
    contrast: ContrastLevel,
): ColorScheme =
    dynamicColorScheme(
        seedColor = seed,
        isDark = dark,
        isAmoled = amoled,
        style = PaletteStyle.TonalSpot,
        contrastLevel = contrast.value,
    )

/**
 * The wallpaper scheme. Android's own scheme is used as it is at standard contrast. For
 * medium and high contrast (which Android's scheme has no parameter for) the scheme is
 * regenerated from the wallpaper's primary colour at that contrast.
 */
private fun dynamicScheme(
    context: Context,
    dark: Boolean,
    amoled: Boolean,
    contrast: ContrastLevel,
): ColorScheme {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return seedScheme(DEFAULT_SEED, dark, amoled, contrast)
    val system = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return when {
        contrast != ContrastLevel.STANDARD -> seedScheme(system.primary, dark, amoled, contrast)
        amoled -> system.withBlackSurfaces()
        else -> system
    }
}

/** AMOLED: pure black backgrounds, with containers kept just off black so layers still read. */
internal fun ColorScheme.withBlackSurfaces() =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = AMOLED_CONTAINER_LOW,
        surfaceContainer = AMOLED_CONTAINER,
        surfaceContainerHigh = AMOLED_CONTAINER_HIGH,
        surfaceContainerHighest = AMOLED_CONTAINER_HIGHEST,
    )

private val AMOLED_CONTAINER_LOW = Color(0xFF0A0A0A)
private val AMOLED_CONTAINER = Color(0xFF121212)
private val AMOLED_CONTAINER_HIGH = Color(0xFF1A1A1A)
private val AMOLED_CONTAINER_HIGHEST = Color(0xFF222222)
