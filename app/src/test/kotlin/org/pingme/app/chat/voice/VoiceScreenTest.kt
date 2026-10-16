// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.Manifest
import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.assertAccessible
import org.pingme.app.chat.COMPOSER
import org.pingme.app.chat.ChatRoute
import org.pingme.app.chat.ChatViewModel
import org.pingme.app.inbox.DemoInbox
import org.pingme.connectors.demo.DemoControls
import org.pingme.core.connector.chat
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.MessageKind
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** The microphone, Voice reply, the MMS size warning, and the voice-note bubble (UI_DESIGN.md 5.5, 5.6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class VoiceScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private lateinit var vm: ChatViewModel

    private fun open(
        chat: String,
        settings: (DemoControls.Settings) -> DemoControls.Settings = { it },
    ) {
        shadowOf(
            ApplicationProvider.getApplicationContext<Application>(),
        ).grantPermissions(Manifest.permission.RECORD_AUDIO)
        demo = DemoInbox(temp.root)
        demo.controls.update { settings(it.copy(liveActivity = false)) }
        runBlocking { demo.seed() }
        vm = demo.chatViewModel(chat)
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) { ChatRoute(vm.chatId, onBack = {}, viewModel = vm) }
        }
        waitFor {
            vm.state.value.items
                .isNotEmpty()
        }
    }

    @After
    fun tearDown() = demo.close()

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    private fun hasText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun latest(chat: String) =
        runBlocking {
            demo.messages
                .latest(demo.account.id.chat(chat), 3)
                .first()
                .first()
        }

    @Test
    fun theMicGivesWayToSendOnceThereIsText() {
        open("sam")
        compose.onNode(hasTestTag(MIC)).assertExists()
        compose.onNode(hasTestTag(COMPOSER)).performTextInput("hi")
        waitFor { compose.onAllNodes(hasTestTag(MIC)).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasContentDescription("Send")).assertExists()
    }

    private val mic get() = vm.voice.state.value as? MicState.Recording

    private fun press(block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) =
        compose.onNode(hasTestTag(MIC)).performTouchInput(block)

    private fun voiceSent() = latest("sam").let { it.kind == MessageKind.VOICE && !it.id.value.contains("/pending-") }

    @Test
    fun aTapOnTheMicRecordsHandsFree() {
        open("sam")
        press {
            down(center)
            advanceEventTime(100)
            up()
        }
        waitFor { mic?.locked == true }
        compose.onNode(hasContentDescription("Delete recording")).performClick()
        waitFor { vm.voice.state.value == MicState.Idle }
        assertEquals(1, demo.recorder.cancelled)
    }

    @Test
    fun holdingTheMicRecordsAndLettingGoSends() {
        open("sam")
        press { down(center) }
        waitFor { mic != null }
        assertEquals("held, not hands-free", false, mic?.locked)
        Thread.sleep(RECORD_MS)
        press {
            advanceEventTime(RECORD_MS)
            up()
        }
        waitFor { voiceSent() }
    }

    @Test
    fun slidingLeftWhileHoldingThrowsTheRecordingAway() {
        open("sam")
        press { down(center) }
        waitFor { mic != null }
        press {
            advanceEventTime(300)
            moveBy(Offset(-SLIDE, 0f))
        }
        waitFor { vm.voice.state.value == MicState.Idle }
        assertEquals(1, demo.recorder.cancelled)
        press { up() }
    }

    /** Mid-sentence in the wrong chat: the trash stops it, and the key coming up sends nothing (owner, 2026-10-15). */
    @Test
    fun theTrashStopsAHeldVolumeUpRecordingAndTheReleaseSendsNothing() {
        open("sam")
        // The composer listens for the key only once it has its mic, a moment after the chat shows.
        waitFor { compose.onAllNodes(hasTestTag(MIC)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, 0))
        waitFor { mic != null }
        assertEquals("held, not hands-free", false, mic?.locked)
        // The held bar, as a screen reader sees it (P8.1): its one control is the trash.
        compose.assertAccessible()
        // Past the short-press limit: letting go now would send.
        Thread.sleep(RECORD_MS)
        compose.onNode(hasContentDescription(STOP)).performClick()
        waitFor { vm.voice.state.value == MicState.Idle }
        assertTrue(HardwareKeys.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP))
        waitFor { compose.onAllNodes(hasTestTag(COMPOSER)).fetchSemanticsNodes().isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MS))
        assertEquals(1, demo.recorder.cancelled)
        assertFalse("the release after a stop on screen is not a short hold", hasText(TOO_SHORT))
        assertFalse(voiceSent())
    }

    @Test
    fun theTrashStopsAHeldMicRecordingAndLettingGoSendsNothing() {
        open("sam")
        press { down(center) }
        waitFor { mic != null }
        Thread.sleep(RECORD_MS)
        // A second finger on the trash while the first is still on the mic.
        compose.onNode(hasContentDescription(STOP)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor { vm.voice.state.value == MicState.Idle }
        press {
            advanceEventTime(RECORD_MS)
            up()
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MS))
        assertEquals(1, demo.recorder.cancelled)
        assertFalse(hasText(TOO_SHORT))
        assertFalse(voiceSent())
    }

    @Test
    fun slidingUpLocksThenItPausesPlaysBackGoesOnAndSends() {
        open("sam")
        press { down(center) }
        waitFor { mic != null }
        press {
            advanceEventTime(300)
            moveBy(Offset(0f, -SLIDE))
        }
        waitFor { mic?.locked == true }
        // The hands-free bar has replaced the mic under the finger, which now lifts.
        compose.onRoot().performTouchInput { up() }
        Thread.sleep(RECORD_MS)
        compose.onNode(hasContentDescription("Pause recording")).performClick()
        waitFor { mic?.paused == true }
        compose.onNode(hasContentDescription("Play what you have recorded")).assertExists()
        // The hands-free recording bar, paused, as a screen reader sees it (P8.1).
        compose.assertAccessible()
        compose.onNode(hasContentDescription("Go on recording")).performClick()
        waitFor { mic?.paused == false }
        compose.onNode(hasContentDescription("Send")).performClick()
        waitFor { voiceSent() }
    }

    @Test
    fun voiceReplyRecordsHandsFreeAndSendsAQuotedNote() {
        open("sam")
        val message = latest("sam")
        compose.onNodeWithText(message.body!!).performTouchInput { longClick() }
        compose.onNodeWithText("Voice reply").performClick()
        waitFor { (vm.voice.state.value as? MicState.Recording)?.locked == true }
        waitFor { hasText("Replying to Sam Ortiz") }
        Thread.sleep(RECORD_MS)
        compose.onNode(hasContentDescription("Send")).performClick()
        waitFor { latest("sam").let { it.kind == MessageKind.VOICE && !it.id.value.contains("pending") } }
        val note = latest("sam")
        assertEquals(message.id, note.replyTo)
        assertEquals(AttachmentKind.VOICE, note.attachments.single().kind)
    }

    @Test
    fun aBigVoiceNoteOnMmsAsksFirst() {
        open("sam") { it.copy(capabilities = DemoControls.MINIMAL) }
        demo.recorder.bytes = 2_000_000
        vm.voice.start(locked = true)
        Thread.sleep(RECORD_MS)
        vm.voice.send()
        waitFor { hasText("This may be too big for MMS") }
        compose.onNodeWithText("Send anyway").performClick()
        waitFor { latest("sam").kind == MessageKind.VOICE }
    }

    @Test
    fun aVoiceNoteBubbleChangesSpeed() {
        open("mom")
        // The bubble reads as one item to screen readers, so its parts are found inside it.
        waitFor {
            compose
                .onAllNodes(
                    hasTestTag(VOICE_NOTE),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("1x", useUnmergedTree = true).performClick()
        waitFor { compose.onAllNodesWithText("1.5x", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val RECORD_MS = 800L
        const val SETTLE_MS = 500L
        const val STOP = "Stop without sending"
        const val TOO_SHORT = "Too short to send. Tap the mic to record hands-free, or hold it and talk"

        // Far enough to cancel or lock (120 and 90 dp) on this screen.
        const val SLIDE = 500f
        val STEP: Duration = Duration.ofMillis(50)
    }
}
