// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Where the composer's microphone is (UI_DESIGN.md 5.6). */
sealed interface MicState {
    data object Idle : MicState

    /**
     * Recording. [locked] means hands-free, waiting for send or delete; [levels] is the live
     * waveform, newest last.
     */
    data class Recording(
        val elapsedMs: Long = 0,
        val levels: List<Float> = emptyList(),
        val locked: Boolean = false,
    ) : MicState
}

/**
 * Hold to record, slide left to cancel, slide up to lock, release or tap send to send
 * (UI_DESIGN.md 5.6). [onRecorded] hears each finished voice note.
 */
class VoiceNotes(
    private val scope: CoroutineScope,
    private val recorder: VoiceRecorder,
    private val clock: Clock,
    /** True where voice notes go as MMS, so they record at a lower quality to fit the carrier's limit. */
    private val compact: () -> Boolean,
    private val onRecorded: (Recording) -> Unit,
) {
    private val mic = MutableStateFlow<MicState>(MicState.Idle)
    private val asked = MutableStateFlow(false)
    private var ticker: Job? = null

    val state: StateFlow<MicState> = mic.asStateFlow()

    /** True while "Voice reply" waits for the composer to check the microphone permission and start. */
    val lockedRequest: StateFlow<Boolean> = asked.asStateFlow()

    /** "Voice reply": the composer starts a locked recording as soon as it may use the microphone. */
    fun requestLocked() {
        asked.value = true
    }

    fun clearRequest() {
        asked.value = false
    }

    /** Starts recording, held or already locked (the "Voice reply" action); false if the mic would not open. */
    fun start(locked: Boolean = false): Boolean {
        if (mic.value is MicState.Recording) return true
        if (!recorder.start(now(), compact())) return false
        mic.value = MicState.Recording(locked = locked)
        val started = now()
        ticker =
            scope.launch {
                while (isActive) {
                    delay(SAMPLE_MS)
                    mic.update { state ->
                        if (state !is MicState.Recording) return@update state
                        state.copy(
                            elapsedMs = now() - started,
                            levels = (state.levels + recorder.level()).takeLast(LIVE_BARS),
                        )
                    }
                }
            }
        return true
    }

    fun lock() = mic.update { if (it is MicState.Recording) it.copy(locked = true) else it }

    /** Sends what was recorded; false when it was too short to keep. */
    fun send(): Boolean {
        if (mic.value !is MicState.Recording) return false
        stopTicking()
        val made = recorder.finish(now())
        made?.let(onRecorded)
        return made != null
    }

    fun cancel() {
        if (mic.value !is MicState.Recording) return
        stopTicking()
        recorder.cancel()
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
        mic.value = MicState.Idle
    }

    private fun now() = clock.now().toEpochMilliseconds()

    companion object {
        const val SAMPLE_MS = 100L

        /** How many bars the live waveform keeps. */
        const val LIVE_BARS = 40
    }
}
