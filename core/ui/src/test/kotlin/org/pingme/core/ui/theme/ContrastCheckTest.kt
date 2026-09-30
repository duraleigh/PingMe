// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastCheckTest {
    private fun issues(appearance: Appearance): List<ContrastIssue> {
        val dark = appearance.mode == ThemeMode.DARK
        val scheme =
            seedScheme(
                androidx.compose.ui.graphics
                    .Color(ThemePreset.PINGME.seed),
                dark,
                amoled = false,
                contrast = appearance.contrast,
            )
        return contrastIssues(
            appearance,
            scheme,
            NetworkPalette(scheme, dark, appearance.contrast, appearance.networkColors, appearance.networkAccents),
        )
    }

    @Test
    fun theDefaultLookPassesInLightAndDark() {
        assertEquals(emptyList<ContrastIssue>(), issues(Appearance(mode = ThemeMode.LIGHT)))
        assertEquals(emptyList<ContrastIssue>(), issues(Appearance(mode = ThemeMode.DARK)))
    }

    @Test
    fun aMidToneFilledBubbleIsFlagged() {
        // Too light for white text and too dark for black: nothing reaches 4.5 to 1.
        val midTone = 0xFF808080.toInt()
        val found =
            issues(
                Appearance(
                    mode = ThemeMode.LIGHT,
                    bubbleStyle = BubbleStyle.FILLED,
                    networkAccents =
                        mapOf(org.pingme.core.model.NetworkId.WHATSAPP to midTone),
                ),
            )
        assertTrue(found.toString(), found.any { it.what == "WhatsApp bubbles" && it.ratio < MIN_TEXT_CONTRAST })
    }

    @Test
    fun aWhiteWallpaperInDarkModeIsFlagged() {
        val found = issues(Appearance(mode = ThemeMode.DARK, wallpaper = ChatWallpaper.Colour(0xFFFFFFFF.toInt())))
        assertEquals(listOf("Dates and notices on the wallpaper"), found.map { it.what })
    }
}
