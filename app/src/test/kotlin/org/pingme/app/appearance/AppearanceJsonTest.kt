// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.pingme.app.appearance.AppearanceRepository.Companion.decode
import org.pingme.app.appearance.AppearanceRepository.Companion.decodeOrDefault
import org.pingme.app.appearance.AppearanceRepository.Companion.encode
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.theme.AppIcon
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.BubbleStyle
import org.pingme.core.ui.theme.BundledFont
import org.pingme.core.ui.theme.ChatWallpaper
import org.pingme.core.ui.theme.ColorSource
import org.pingme.core.ui.theme.ContrastLevel
import org.pingme.core.ui.theme.FontChoice
import org.pingme.core.ui.theme.Haptics
import org.pingme.core.ui.theme.InboxDensity
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PinnedStyle
import org.pingme.core.ui.theme.ShapeFamily
import org.pingme.core.ui.theme.SwipeAction
import org.pingme.core.ui.theme.ThemeMode
import org.pingme.core.ui.theme.TimestampMode

/** The theme file (UI_DESIGN.md 4): everything survives a save and a load. */
class AppearanceJsonTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val everythingChanged =
        Appearance(
            colorSource =
                ColorSource.Manual(
                    0xFF336699.toInt(),
                    secondary = 0xFF993366.toInt(),
                    tertiary = null,
                    neutral = 0xFF777777.toInt(),
                ),
            mode = ThemeMode.DARK,
            amoled = true,
            contrast = ContrastLevel.HIGH,
            networkColors = mapOf(NetworkId.SIGNAL to 0xFF2266FF.toInt()),
            networkAccents = mapOf(NetworkId.WHATSAPP to 0xFF00AA55.toInt()),
            shapeFamily = ShapeFamily.SHARP,
            bubbleCorner = 12f,
            bubbleTails = false,
            bubbleStyle = BubbleStyle.GRADIENT,
            inboxDensity = InboxDensity.COMPACT,
            pinnedStyle = PinnedStyle.ROW,
            avatarSize = 40f,
            showAvatarsInChat = false,
            wallpaper = ChatWallpaper.Gradient(0xFF101010.toInt(), 0xFF303050.toInt()),
            timestamps = TimestampMode.ALWAYS,
            uiFont = FontChoice.Bundled(BundledFont.LEXEND),
            messageFont = FontChoice.Imported("/fonts/mine.ttf", "Mine"),
            fontScale = 1.2f,
            lineHeightScale = 1.3f,
            emphasizedHeadlines = false,
            motion = MotionIntensity.SUBTLE,
            haptics = Haptics.OFF,
            appIcon = AppIcon.SUNSET,
            swipeRight = SwipeAction.PIN,
            swipeLeft = SwipeAction.DELETE,
        )

    @Test
    fun everySettingSurvivesASaveAndLoad() {
        assertEquals(everythingChanged, decode(encode(everythingChanged)))
    }

    @Test
    fun aBrokenFileGivesTheDefaultLook() {
        assertEquals(Appearance(), decodeOrDefault("{ not json"))
        assertEquals(Appearance(), decodeOrDefault(null))
    }

    @Test
    fun aFileFromANewerVersionStillLoads() {
        val newer = encode(everythingChanged).replaceFirst("{", "{\"somethingNew\":true,")
        assertEquals(everythingChanged, decode(newer))
    }

    @Test
    fun missingFontsAndPicturesFallBackToDefaults() {
        val font = folder.newFile("kept.ttf")
        val appearance =
            Appearance(
                uiFont = FontChoice.Imported(font.path, "Kept"),
                messageFont = FontChoice.Imported(folder.root.resolve("gone.ttf").path, "Gone"),
                wallpaper = ChatWallpaper.Image(folder.root.resolve("gone.jpg").path, blurred = true),
            ).withoutMissingFiles()
        assertEquals(FontChoice.Imported(font.path, "Kept"), appearance.uiFont)
        assertEquals(Appearance().messageFont, appearance.messageFont)
        assertEquals(ChatWallpaper.None, appearance.wallpaper)
    }
}
