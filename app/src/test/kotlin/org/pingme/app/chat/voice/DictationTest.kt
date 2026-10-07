// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
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
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Volume down held dictates into the box through Groq (owner, 2026-10-06). */
@RunWith(RobolectricTestRunner::class)
class DictationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = Instant.parse("2026-10-06T11:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val recorder by lazy { FakeRecorder(ApplicationProvider.getApplicationContext(), temp.root) }
    private var key = "gsk_test"
    private val sentFiles = mutableListOf<File>()
    private var answer: suspend (File, String) -> String = { file, _ ->
        sentFiles += file
        "hello there"
    }

    private fun dictation(scope: kotlinx.coroutines.CoroutineScope) =
        Dictation(scope, recorder, clock, { key }, transcribe = { file, k -> answer(file, k) })

    @Test
    fun heldThenReleasedSendsTheRecordingAndHandsBackTheWords() =
        runTest {
            val d = dictation(backgroundScope)
            val words = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.results.collect { words += it } }
            assertTrue(d.ready)
            assertTrue(d.start())
            assertEquals(DictationState.Listening, d.state.value)
            now += 3.seconds
            assertTrue(d.stop())
            runCurrent()
            assertEquals(listOf("hello there"), words)
            assertEquals(DictationState.Idle, d.state.value)
            assertEquals(1, sentFiles.size)
            assertFalse("the recording is deleted once the words are back", sentFiles.single().exists())
        }

    @Test
    fun aShortPressIsCancelledAndNothingLeavesThePhone() =
        runTest {
            val d = dictation(backgroundScope)
            assertTrue(d.start())
            d.cancel()
            assertEquals(DictationState.Idle, d.state.value)
            assertEquals(1, recorder.cancelled)
            assertTrue(sentFiles.isEmpty())
        }

    @Test
    fun groqsRefusalIsShownNotSwallowed() =
        runTest {
            answer = { _, _ -> error("Groq answered 401 invalid key") }
            val d = dictation(backgroundScope)
            assertTrue(d.start())
            now += 2.seconds
            assertTrue(d.stop())
            runCurrent()
            val failed = d.state.value as DictationState.Failed
            assertTrue(failed.reason.contains("401"))
            // A failed dictation does not block the next one.
            assertTrue(d.start())
        }

    @Test
    fun withoutAKeyThereIsNoDictation() {
        key = " "
        assertFalse(dictation(kotlinx.coroutines.GlobalScope).ready)
    }

    @Test
    fun withTheSwitchOffThereIsNoDictationEvenWithAKey() {
        val off =
            Dictation(
                kotlinx.coroutines.GlobalScope,
                recorder,
                clock,
                { key },
                { file, k -> answer(file, k) },
            ) { false }
        assertFalse(off.ready)
    }
}
