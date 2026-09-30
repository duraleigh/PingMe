// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import kotlinx.serialization.Serializable
import org.pingme.core.model.NetworkId

/**
 * Everything the theme is built from (UI_DESIGN.md 2.1, 4). Serializable, because themes
 * can be exported and imported as JSON (UI_DESIGN.md 4).
 */
@Serializable
data class Appearance(
    val colorSource: ColorSource = ColorSource.Dynamic,
    val mode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    /** Pure black surfaces in dark mode. */
    val amoled: Boolean = false,
    val contrast: ContrastLevel = ContrastLevel.STANDARD,
    val motion: MotionIntensity = MotionIntensity.FULL,
    val shapeFamily: ShapeFamily = ShapeFamily.EXPRESSIVE,
    val uiFont: FontChoice = FontChoice.Bundled(BundledFont.ROBOTO_FLEX),
    val messageFont: FontChoice = FontChoice.Bundled(BundledFont.ROBOTO_FLEX),
    /** In-app text size, on top of the system font scale. */
    val fontScale: Float = 1f,
    val lineHeightScale: Float = 1f,
    val emphasizedHeadlines: Boolean = true,
    /** Signature colour overrides per network (UI_DESIGN.md 4.1, 10.1), as ARGB. */
    val networkColors: Map<NetworkId, Int> = emptyMap(),
)

/** Where the colour scheme comes from (UI_DESIGN.md 4.1). */
@Serializable
sealed interface ColorSource {
    /** Material You: from the wallpaper (Android 12+). The default. */
    @Serializable
    data object Dynamic : ColorSource

    /** A tonal scheme generated from one colour the user picked. */
    @Serializable
    data class Seed(
        val argb: Int,
    ) : ColorSource

    /** One of the curated [ThemePreset]s. */
    @Serializable
    data class Preset(
        val name: String,
    ) : ColorSource

    /** A fully manual palette: each key colour chosen by hand; null ones follow [primary]. */
    @Serializable
    data class Manual(
        val primary: Int,
        val secondary: Int? = null,
        val tertiary: Int? = null,
        val neutral: Int? = null,
    ) : ColorSource
}

@Serializable
enum class ThemeMode { LIGHT, DARK, FOLLOW_SYSTEM }

/** Contrast levels, mapped to the Material colour scheme variants. */
@Serializable
enum class ContrastLevel(
    val value: Double,
) {
    STANDARD(0.0),
    MEDIUM(0.5),
    HIGH(1.0),
}

/**
 * Animation intensity (UI_DESIGN.md 4.5). Off also switches to the standard motion scheme,
 * and the system "remove animations" setting always wins.
 */
@Serializable
enum class MotionIntensity { OFF, SUBTLE, FULL, EXTRA }

/** The shape family that flows through the whole app (UI_DESIGN.md 4.2). */
@Serializable
enum class ShapeFamily { ROUND, SOFT, SHARP, EXPRESSIVE }

@Serializable
sealed interface FontChoice {
    @Serializable
    data class Bundled(
        val font: BundledFont,
    ) : FontChoice

    /** A .ttf or .otf the user imported, copied into app storage (UI_DESIGN.md 4.4). */
    @Serializable
    data class Imported(
        val path: String,
        val displayName: String,
    ) : FontChoice
}

/** The bundled variable fonts, all SIL Open Font License (UI_DESIGN.md 4.4). */
@Serializable
enum class BundledFont(
    val displayName: String,
) {
    ROBOTO_FLEX("Roboto Flex"),
    INTER("Inter"),
    MANROPE("Manrope"),
    NUNITO("Nunito"),
    LEXEND("Lexend"),
    ATKINSON_HYPERLEGIBLE("Atkinson Hyperlegible"),
    JETBRAINS_MONO("JetBrains Mono"),
}

/** Curated colour presets (UI_DESIGN.md 4.1). */
enum class ThemePreset(
    val displayName: String,
    val seed: Int,
) {
    PINGME("PingMe", 0xFF5A3FCF.toInt()),
    VIOLET("Violet", 0xFF6750A4.toInt()),
    OCEAN("Ocean", 0xFF006A6A.toInt()),
    FOREST("Forest", 0xFF386A20.toInt()),
    SUNSET("Sunset", 0xFFC2482A.toInt()),
    ROSE("Rose", 0xFFB02A6A.toInt()),
    GOLD("Gold", 0xFF7A5C00.toInt()),
    GRAPHITE("Graphite", 0xFF5A5A66.toInt()),
    ;

    companion object {
        fun named(name: String) = entries.firstOrNull { it.name == name } ?: PINGME
    }
}
