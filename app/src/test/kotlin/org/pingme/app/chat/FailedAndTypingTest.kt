// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.model.ChatKind
import org.pingme.core.ui.theme.PingMeTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FailedAndTypingTest {
    @get:Rule
    val compose = createComposeRule()

    /** A long reason squeezed Retry out of sight (owner, 2026-11-01: the Nick text). */
    @Test
    fun aLongReasonLeavesBothButtonsOnScreen() {
        var retried = 0
        var deleted = 0
        compose.setContent {
            PingMeTheme {
                FailedRow(
                    "Could not send: http2: timeout awaiting response headers, and then a good deal more",
                    { retried++ },
                    { deleted++ },
                )
            }
        }
        compose
            .onNodeWithText("Try again")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(40.dp)
            .performClick()
        compose
            .onNodeWithText("Delete")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(40.dp)
            .performClick()
        assertEquals(1, retried)
        assertEquals(1, deleted)
    }

    @Test
    fun someoneTypingShowsABubbleAndAGroupSaysWho() {
        compose.setContent { PingMeTheme { TypingBubble(listOf("Priya Shah"), ChatKind.GROUP) } }
        compose.onNodeWithTag(TYPING_BUBBLE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Priya is typing…").assertIsDisplayed()
    }

    @Test
    fun nobodyTypingShowsNoBubble() {
        compose.setContent { PingMeTheme { TypingBubble(emptyList(), ChatKind.DIRECT) } }
        compose.onNodeWithTag(TYPING_BUBBLE, useUnmergedTree = true).assertDoesNotExist()
    }
}
