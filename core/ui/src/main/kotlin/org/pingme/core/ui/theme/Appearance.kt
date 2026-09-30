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
    /** Outgoing bubble colour per network (UI_DESIGN.md 4.1, 10.1), as ARGB. */
    val networkColors: Map<NetworkId, Int> = emptyMap(),
    /** Accent colour per network for badges and the chat header (UI_DESIGN.md 4.1), as ARGB. */
    val networkAccents: Map<NetworkId, Int> = emptyMap(),
    val senderNameColors: SenderNameColors = SenderNameColors.AUTO,
    /** Shape (UI_DESIGN.md 4.2). Bubble corner radius in dp; null follows the shape family. */
    val bubbleCorner: Float? = null,
    val bubbleTails: Boolean = true,
    val bubbleStyle: BubbleStyle = BubbleStyle.TONAL,
    // Layout and density (UI_DESIGN.md 4.3)
    val inboxDensity: InboxDensity = InboxDensity.COMFORTABLE,
    val pinnedStyle: PinnedStyle = PinnedStyle.GRID,
    val avatarSize: Float = DEFAULT_AVATAR_SIZE,
    val showAvatarsInChat: Boolean = true,
    val wallpaper: ChatWallpaper = ChatWallpaper.None,
    val timestamps: TimestampMode = TimestampMode.GROUPED,
    // Motion and feedback (UI_DESIGN.md 4.5)
    val haptics: Haptics = Haptics.LIGHT,
    // Icon and shortcuts (UI_DESIGN.md 4.6)
    val appIcon: AppIcon = AppIcon.DEFAULT,
    val swipeRight: SwipeAction = SwipeAction.MARK_READ,
    val swipeLeft: SwipeAction = SwipeAction.ARCHIVE,
) {
    companion object {
        const val DEFAULT_AVATAR_SIZE = 48f
        val AVATAR_SIZES = 32f..64f
        val BUBBLE_CORNERS = 4f..28f
        val FONT_SCALES = 0.8f..1.6f
        val LINE_HEIGHTS = 0.9f..1.5f
    }
}

/** Sender names in group chats: a colour per person, or one colour for all (UI_DESIGN.md 4.1). */
@Serializable
enum class SenderNameColors { AUTO, FIXED }

/** UI_DESIGN.md 4.2. */
@Serializable
enum class BubbleStyle { TONAL, OUTLINED, FILLED, GRADIENT, PILL }

/** UI_DESIGN.md 4.3. */
@Serializable
enum class InboxDensity { COMFORTABLE, COMPACT, SPACIOUS }

/** How pinned chats show on the inbox (UI_DESIGN.md 3.1, 4.3). */
@Serializable
enum class PinnedStyle { ROW, GRID, TOP_OF_LIST }

/** UI_DESIGN.md 4.3. */
@Serializable
enum class TimestampMode { ALWAYS, ON_TAP, GROUPED }

/** UI_DESIGN.md 4.5. */
@Serializable
enum class Haptics { OFF, LIGHT, STRONG }

/** What a swipe on an inbox row does (UI_DESIGN.md 3.1). Each direction is set on its own. */
@Serializable
enum class SwipeAction { PIN, ARCHIVE, MUTE, MARK_READ, LOW_PRIORITY, DELETE, OFF }

/** App icon variants, switched through activity aliases (UI_DESIGN.md 4.6). */
@Serializable
enum class AppIcon(
    val displayName: String,
) {
    DEFAULT("Indigo"),
    LIGHT("Light"),
    DARK("Dark"),
    SUNSET("Sunset"),
    FOREST("Forest"),
}

/** The chat background (UI_DESIGN.md 4.3). Per chat overrides come in Chat details (P2.5). */
@Serializable
sealed interface ChatWallpaper {
    @Serializable
    data object None : ChatWallpaper

    @Serializable
    data class Colour(
        val argb: Int,
    ) : ChatWallpaper

    @Serializable
    data class Gradient(
        val from: Int,
        val to: Int,
    ) : ChatWallpaper

    /** A picture copied into app storage; [blurred] softens it behind the bubbles. */
    @Serializable
    data class Image(
        val path: String,
        val blurred: Boolean,
    ) : ChatWallpaper
}

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
