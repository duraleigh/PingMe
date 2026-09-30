// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

/**
 * The app theme (BUILD_PLAN.md P2.1): `MaterialExpressiveTheme` with PingMe's colour
 * scheme, motion scheme, shapes, and type, plus what Material has no slot for: the shape
 * family's polygons, the network palette, the message text style, and the effective
 * motion intensity. Read those through [PingMeTheme].
 */
@Composable
fun PingMeTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark =
        when (appearance.mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        }
    val colorScheme =
        remember(appearance.colorSource, appearance.contrast, appearance.amoled, dark) {
            resolveColorScheme(context, appearance, dark)
        }
    val motion = effectiveMotion(context, appearance.motion)
    val shapes = remember(appearance.shapeFamily) { PingMeShapes.of(appearance.shapeFamily) }
    val typography =
        remember(appearance.uiFont, appearance.fontScale, appearance.lineHeightScale) {
            pingMeTypography(fontFamilyFor(appearance.uiFont), appearance.fontScale, appearance.lineHeightScale)
        }
    val messageFamily = remember(appearance.messageFont) { fontFamilyFor(appearance.messageFont) }
    val palette =
        remember(colorScheme, dark, appearance.contrast, appearance.networkColors, appearance.networkAccents) {
            NetworkPalette(colorScheme, dark, appearance.contrast, appearance.networkColors, appearance.networkAccents)
        }
    CompositionLocalProvider(
        LocalAppearance provides appearance,
        LocalPingMeShapes provides shapes,
        LocalNetworkPalette provides palette,
        LocalMotionIntensity provides motion,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = motionSchemeFor(motion),
            shapes = shapes.components,
            typography = typography,
        ) {
            val messageStyle = typography.bodyLarge.copy(fontFamily = messageFamily)
            CompositionLocalProvider(LocalMessageTextStyle provides messageStyle, content = content)
        }
    }
}

/** PingMe's additions to [MaterialTheme]. */
object PingMeTheme {
    val appearance: Appearance
        @Composable @ReadOnlyComposable
        get() = LocalAppearance.current

    val shapes: PingMeShapes
        @Composable @ReadOnlyComposable
        get() = LocalPingMeShapes.current

    val networkColors: NetworkPalette
        @Composable @ReadOnlyComposable
        get() = LocalNetworkPalette.current

    /** The text style for message bodies, in the message font (UI_DESIGN.md 4.4). */
    val messageText: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalMessageTextStyle.current

    /** How much to animate, after the system reduce-motion setting (UI_DESIGN.md 4.5). */
    val motion: MotionIntensity
        @Composable @ReadOnlyComposable
        get() = LocalMotionIntensity.current

    /** Headline and chat-title style: emphasized unless the user turned that off (UI_DESIGN.md 4.4). */
    val headline: TextStyle
        @Composable @ReadOnlyComposable
        get() =
            if (appearance.emphasizedHeadlines) {
                MaterialTheme.typography.headlineMediumEmphasized
            } else {
                MaterialTheme.typography.headlineMedium
            }

    val chatTitle: TextStyle
        @Composable @ReadOnlyComposable
        get() =
            if (appearance.emphasizedHeadlines) {
                MaterialTheme.typography.titleMediumEmphasized
            } else {
                MaterialTheme.typography.titleMedium
            }
}

private val LocalAppearance = staticCompositionLocalOf { Appearance() }
private val LocalPingMeShapes = staticCompositionLocalOf { PingMeShapes.of(ShapeFamily.EXPRESSIVE) }
private val LocalNetworkPalette = staticCompositionLocalOf<NetworkPalette> { error("PingMeTheme is not set") }
private val LocalMessageTextStyle = staticCompositionLocalOf { TextStyle.Default }
private val LocalMotionIntensity = staticCompositionLocalOf { MotionIntensity.FULL }
