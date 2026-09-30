// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun amoledMakesDarkSurfacesBlackAndLightModeIgnoresIt() {
        val appearance = Appearance(colorSource = ColorSource.Preset(ThemePreset.VIOLET.name), amoled = true)
        assertEquals(Color.Black, resolveColorScheme(context, appearance, dark = true).background)
        assertTrue(resolveColorScheme(context, appearance, dark = false).background != Color.Black)
    }

    @Test
    fun higherContrastStrengthensTextOutlinesAndPrimary() {
        fun schemeAt(level: ContrastLevel) =
            resolveColorScheme(
                context,
                Appearance(colorSource = ColorSource.Seed(0xFF1B8AC4.toInt()), contrast = level),
                dark = false,
            )
        // The pairs Material's contrast levels are defined to strengthen. Container pairs are
        // not monotonic across levels in Material's own spec, so they are not checked.
        val pairs =
            listOf<(ColorScheme) -> Double>(
                { contrastRatio(it.surface, it.onSurfaceVariant) },
                { contrastRatio(it.surface, it.outline) },
                { contrastRatio(it.primary, it.onPrimary) },
            )
        pairs.forEach { pair ->
            assertTrue(pair(schemeAt(ContrastLevel.HIGH)) > pair(schemeAt(ContrastLevel.MEDIUM)))
            assertTrue(pair(schemeAt(ContrastLevel.MEDIUM)) > pair(schemeAt(ContrastLevel.STANDARD)))
        }
    }

    @Test
    fun dynamicColourWorksWithAndWithoutWallpaperColours() {
        // Robolectric runs API 35, which has wallpaper colours; either path must give a full scheme.
        val scheme = resolveColorScheme(context, Appearance(colorSource = ColorSource.Dynamic), dark = false)
        assertTrue(scheme.primary != Color.Unspecified)
        val high =
            resolveColorScheme(
                context,
                Appearance(colorSource = ColorSource.Dynamic, contrast = ContrastLevel.HIGH),
                dark = true,
            )
        assertTrue(high.primary != Color.Unspecified)
    }

    @Test
    fun theBundledFontsAreVariable() {
        File("src/main/res/font").listFiles()!!.forEach { assertTrue(it.name, isVariableFont(it)) }
        val notAFont = File.createTempFile("plain", ".ttf").apply { writeBytes(ByteArray(64)) }
        assertFalse(isVariableFont(notAFont))
    }

    @Test
    fun everyCombinationRendersTheSample() {
        var appearance by mutableStateOf(Appearance())
        compose.setContent { PingMeTheme(appearance) { ThemeSample() } }
        val combinations =
            ShapeFamily.entries.flatMap { shape ->
                listOf(
                    ColorSource.Dynamic,
                    ColorSource.Seed(0xFF0B6E62.toInt()),
                    ColorSource.Preset(ThemePreset.SUNSET.name),
                    ColorSource.Manual(0xFF5A3FCF.toInt(), tertiary = 0xFFD6247A.toInt()),
                ).map { source -> Appearance(colorSource = source, shapeFamily = shape) }
            } +
                BundledFont.entries.map {
                    Appearance(uiFont = FontChoice.Bundled(it), messageFont = FontChoice.Bundled(it), fontScale = 1.3f)
                } +
                ThemeMode.entries.map { Appearance(mode = it, amoled = true) } +
                MotionIntensity.entries.map { Appearance(motion = it) }
        combinations.forEach {
            appearance = it
            compose.waitForIdle()
            compose.onNodeWithText("WhatsApp").assertIsDisplayed()
        }
    }

    @Test
    fun motionOffUsesTheStandardScheme() {
        var standard: Any? = null
        var expressive: Any? = null
        var motion by mutableStateOf(MotionIntensity.OFF)
        compose.setContent {
            PingMeTheme(Appearance(motion = motion)) {
                val scheme = MaterialTheme.motionScheme
                if (motion == MotionIntensity.OFF) standard = scheme else expressive = scheme
            }
        }
        compose.waitForIdle()
        motion = MotionIntensity.FULL
        compose.waitForIdle()
        assertEquals(motionSchemeFor(MotionIntensity.OFF)::class, standard!!::class)
        assertTrue(standard!!::class != expressive!!::class)
    }
}
