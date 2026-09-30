// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.ChatWallpaper
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2400dp-xhdpi")
class AppearanceScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var appearance by mutableStateOf(Appearance(mode = ThemeMode.LIGHT))
    private var resetAsked = false

    private fun show() {
        compose.setContent {
            PingMeTheme(appearance) {
                AppearanceScreen(
                    appearance = appearance,
                    onChange = { change -> appearance = change(appearance) },
                    actions = AppearanceActions({}, {}, {}, {}, {}, {}, onResetAll = { resetAsked = true }),
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun choosingDarkChangesTheLookAtOnce() {
        show()
        scrollTo("Dark")
        compose.onNodeWithText("Dark").performClick()
        compose.waitForIdle()
        assertEquals(ThemeMode.DARK, appearance.mode)
    }

    @Test
    fun hardToReadCombinationsAreWarnedAbout() {
        appearance = Appearance(mode = ThemeMode.DARK, wallpaper = ChatWallpaper.Colour(0xFFFFFFFF.toInt()))
        show()
        compose.onNodeWithText("Some text will be hard to read").assertExists()
        compose.onNodeWithText("Dates and notices on the wallpaper", substring = true).assertExists()
    }

    @Test
    fun theDefaultLookHasNoWarning() {
        show()
        compose.onNodeWithText("Some text will be hard to read").assertDoesNotExist()
    }

    @Test
    fun resetEverythingIsReachable() {
        show()
        scrollTo("Reset everything")
        compose.onNodeWithText("Reset everything").performClick()
        assertTrue(resetAsked)
    }

    /** Renders the studio to build/screenshots/appearance.png so it can be looked at. */
    @Test
    fun screenshot() {
        show()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots")
            .apply { mkdirs() }
            .resolve("appearance.png")
            .outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
