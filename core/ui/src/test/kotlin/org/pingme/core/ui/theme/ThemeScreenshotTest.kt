// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the theme sample in three looks to build/screenshots/theme.png, so the palette can
 * be looked at, not only measured. Fails if rendering fails.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w720dp-h900dp-xhdpi")
class ThemeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun themeSampleRendersInThreeLooks() {
        compose.setContent {
            Row {
                PingMeTheme(
                    Appearance(colorSource = ColorSource.Preset(ThemePreset.PINGME.name), mode = ThemeMode.LIGHT),
                ) {
                    ThemeSample(Modifier.width(240.dp))
                }
                PingMeTheme(
                    Appearance(
                        colorSource = ColorSource.Preset(ThemePreset.PINGME.name),
                        mode = ThemeMode.DARK,
                        shapeFamily = ShapeFamily.ROUND,
                    ),
                ) {
                    ThemeSample(Modifier.width(240.dp))
                }
                PingMeTheme(
                    Appearance(
                        colorSource = ColorSource.Preset(ThemePreset.OCEAN.name),
                        mode = ThemeMode.DARK,
                        amoled = true,
                        shapeFamily = ShapeFamily.SHARP,
                        uiFont = FontChoice.Bundled(BundledFont.LEXEND),
                    ),
                ) {
                    ThemeSample(Modifier.width(240.dp))
                }
            }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots")
            .apply {
                mkdirs()
            }.resolve("theme.png")
            .outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
