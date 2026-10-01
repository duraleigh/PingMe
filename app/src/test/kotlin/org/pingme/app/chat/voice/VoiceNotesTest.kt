// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** Hold, lock, cancel, and send for voice notes (UI_DESIGN.md 5.6). */
@RunWith(RobolectricTestRunner::class)
class VoiceNotesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = Instant.parse("2026-10-01T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val recorder by lazy { FakeRecorder(ApplicationProvider.getApplicationContext(), temp.root) }
    private val sent = mutableListOf<Recording>()
    private var mms = false
    private val notes by lazy {
        VoiceNotes(
            TestScope(StandardTestDispatcher()),
            recorder,
            clock,
            { mms },
        ) { sent += it }
    }

    @Test
    fun holdingThenLettingGoSendsTheNote() {
        assertTrue(notes.start())
        now += 3.seconds
        assertTrue(notes.send())
        assertEquals(3_000L, sent.single().durationMs)
        assertEquals(MicState.Idle, notes.state.value)
    }

    @Test
    fun aQuickTapSendsNothing() {
        notes.start()
        now += 0.2.seconds
        assertFalse(notes.send())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun slidingAwayThrowsTheRecordingAway() {
        notes.start()
        now += 2.seconds
        notes.cancel()
        assertEquals(1, recorder.cancelled)
        assertTrue(sent.isEmpty())
        assertEquals(MicState.Idle, notes.state.value)
    }

    @Test
    fun lockingKeepsRecordingHandsFree() {
        notes.start()
        notes.lock()
        assertEquals(true, (notes.state.value as MicState.Recording).locked)
        now += 5.seconds
        notes.send()
        assertEquals(1, sent.size)
    }

    @Test
    fun voiceReplyStartsLocked() {
        notes.requestLocked()
        assertTrue(notes.lockedRequest.value)
        notes.clearRequest()
        notes.start(locked = true)
        assertEquals(true, (notes.state.value as MicState.Recording).locked)
    }

    @Test
    fun mmsNetworksRecordSmaller() {
        mms = true
        notes.start()
        assertEquals(true, recorder.compact)
    }

    @Test
    fun barsAreScaledToTheLoudest() {
        val bars = Waveform.fold(floatArrayOf(0.1f, 0.2f, 0.4f, 0.2f), count = 2)
        assertEquals(listOf(0.5f, 1f), bars.toList())
        assertEquals("1.5x", speedLabel(1.5f))
        assertEquals("2x", speedLabel(2f))
        assertEquals("1:05", clock(65_000))
    }
}
