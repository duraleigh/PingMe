// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The razr's side key records a voice note in the open chat (owner, 2026-10-06). */
@RunWith(RobolectricTestRunner::class)
class HardwareKeysTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = Instant.parse("2026-10-06T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val sent = mutableListOf<Recording>()
    private val notes by lazy {
        VoiceNotes(
            TestScope(StandardTestDispatcher()),
            FakeRecorder(ApplicationProvider.getApplicationContext(), temp.root),
            clock,
            { false },
        ) { sent += it }
    }

    @Test
    fun theKeyIsLeftAloneUnlessAChatListens() {
        assertFalse(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_SEARCH, 0))
        val gone = HardwareKeys.listen()
        try {
            assertTrue(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_SEARCH, 0))
            // Other keys keep their jobs even while a chat listens.
            assertFalse(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, 0))
        } finally {
            gone()
        }
        assertFalse(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_SEARCH, 0))
    }

    @Test
    fun aHeldKeyCountsOnce() =
        runTest {
            val gone = HardwareKeys.listen()
            try {
                val presses = mutableListOf<Int>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    HardwareKeys.pressed.collect { presses += it }
                }
                assertTrue(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_SEARCH, 0))
                // Repeats while held are taken but not counted as presses.
                assertTrue(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_SEARCH, 1))
                assertTrue(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_SEARCH, 2))
                runCurrent()
                assertEquals(listOf(KeyEvent.KEYCODE_SEARCH), presses)
            } finally {
                gone()
            }
        }

    @Test
    fun theSideKeyOpeningPingMeAgainCountsAsAPressOnlyInAChatInFront() =
        runTest {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val shortcut = Intent(HardwareKeys.ACTION_VOICE_NOTE)
            assertFalse("no chat listening", HardwareKeys.fromIntent(launcher, resumed = true))
            val gone = HardwareKeys.listen()
            try {
                val presses = mutableListOf<Int>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    HardwareKeys.pressed.collect { presses += it }
                }
                assertFalse(
                    "a launcher tap from the background is just opening the app",
                    HardwareKeys.fromIntent(launcher, resumed = false),
                )
                assertTrue(HardwareKeys.fromIntent(launcher, resumed = true))
                assertTrue(
                    "Button Mapper's intent counts even from the background",
                    HardwareKeys.fromIntent(shortcut, resumed = false),
                )
                assertFalse(HardwareKeys.fromIntent(Intent(Intent.ACTION_VIEW), resumed = true))
                runCurrent()
                assertEquals(2, presses.size)
            } finally {
                gone()
            }
        }

    @Test
    fun volumeUpIsTakenDownAndUpOnlyWhileAChatListens() =
        runTest {
            assertFalse(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, 0))
            assertFalse(HardwareKeys.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP))
            val gone = HardwareKeys.listen()
            try {
                val moves = mutableListOf<Boolean>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    HardwareKeys.volumeUp.collect { moves += it }
                }
                assertTrue(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, 0))
                assertTrue("repeats while held are swallowed", HardwareKeys.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, 3))
                assertTrue(HardwareKeys.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP))
                // Volume down is never touched.
                assertFalse(HardwareKeys.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, 0))
                assertFalse(HardwareKeys.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN))
                runCurrent()
                assertEquals(listOf(true, false), moves)
            } finally {
                gone()
            }
        }

    @Test
    fun aShortVolumePressIsVolumeAHeldOneSends() {
        assertEquals(VolumeHold.VOLUME, volumeHoldEnded(150))
        assertEquals(VolumeHold.SEND, volumeHoldEnded(HardwareKeys.HOLD_MS))
        assertEquals(VolumeHold.SEND, volumeHoldEnded(5_000))
    }

    @Test
    fun firstPressAsksForAHandsFreeRecordingSecondPressSends() {
        assertTrue(voiceKeyPressed(notes))
        assertTrue("the composer is asked to start, so it can check the microphone first", notes.lockedRequest.value)
        notes.clearRequest()
        assertTrue(notes.start(locked = true))
        now += 4.seconds
        assertTrue(voiceKeyPressed(notes))
        assertEquals(4_000L, sent.single().durationMs)
        assertEquals(MicState.Idle, notes.state.value)
    }
}
