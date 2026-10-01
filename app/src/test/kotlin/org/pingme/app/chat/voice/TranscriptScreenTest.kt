// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.chat.ChatRoute
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Voice notes written out under the bubble when Settings turns it on (UI_DESIGN.md 5.6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class TranscriptScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(liveActivity = false) }
        runBlocking { demo.seed() }
    }

    @After
    fun tearDown() = demo.close()

    private fun open() {
        val vm = demo.chatViewModel("mom")
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) { ChatRoute(vm.chatId, onBack = {}, viewModel = vm) }
        }
        waitFor { compose.onAllNodesWithTag(VOICE_NOTE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    // The bubble reads as one item to screen readers, so its parts are found inside it.
    private fun shown(text: String) =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theWordsShowUnderTheNoteWhenOn() {
        runBlocking { demo.settings.updateApp { it.copy(media = it.media.copy(transcribeVoice = true)) } }
        open()
        waitFor { shown("See you at seven") }
        assertEquals(1, demo.transcriber.heard)
    }

    @Test
    fun nothingIsWrittenOutWhenOff() {
        open()
        compose.waitForIdle()
        assertTrue(!shown("See you at seven"))
        assertEquals(0, demo.transcriber.heard)
    }

    @Test
    fun aNoteIsOnlyWrittenOutOnce() =
        runBlocking {
            val note = temp.newFile("note.wav")
            demo.transcriber.transcript("k", note)
            demo.transcriber.words = "something else"
            val again = demo.transcriber.transcript("k", note)
            assertEquals(Transcript.Text("See you at seven"), again)
            assertEquals(1, demo.transcriber.heard)
        }

    @Test
    fun wordsThatCannotBeMadeOutSaySo() {
        demo.transcriber.words = null
        runBlocking { demo.settings.updateApp { it.copy(media = it.media.copy(transcribeVoice = true)) } }
        open()
        waitFor { shown("Couldn't make out the words.") }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        val STEP: Duration = Duration.ofMillis(50)
    }
}
