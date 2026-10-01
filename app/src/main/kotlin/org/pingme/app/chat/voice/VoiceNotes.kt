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
     * waveform, newest last. While [paused], [soFar] is what has been recorded, to play back.
     */
    data class Recording(
        val elapsedMs: Long = 0,
        val levels: List<Float> = emptyList(),
        val locked: Boolean = false,
        val paused: Boolean = false,
        val soFar: String? = null,
    ) : MicState
}

/**
 * Voice notes (UI_DESIGN.md 5.6): tap the mic to record hands-free, with pause, play back, and
 * resume; or hold it to record, slide left to cancel, slide up to lock, let go to send.
 * [onRecorded] hears each finished voice note.
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

    // Time recorded before the stretch now going, and when that stretch began.
    private var before = 0L
    private var since = 0L

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
        before = 0
        mic.value = MicState.Recording(locked = locked)
        tick()
        return true
    }

    /** Hands-free only: stops for a while, keeping what is recorded so far to play back. */
    fun pause() {
        val state = mic.value as? MicState.Recording ?: return
        if (!state.locked || state.paused) return
        stopTicker()
        val at = now()
        val soFar = recorder.pause(at)
        before += at - since
        mic.value = state.copy(elapsedMs = before, paused = true, soFar = soFar?.path)
    }

    /** Goes on recording after [pause]. */
    fun resume() {
        val state = mic.value as? MicState.Recording ?: return
        if (!state.paused) return
        if (!recorder.resume(now())) return
        mic.value = state.copy(paused = false, soFar = null)
        tick()
    }

    private fun tick() {
        since = now()
        ticker =
            scope.launch {
                while (isActive) {
                    delay(SAMPLE_MS)
                    mic.update { state ->
                        if (state !is MicState.Recording || state.paused) return@update state
                        state.copy(
                            elapsedMs = before + now() - since,
                            levels = (state.levels + recorder.level()).takeLast(LIVE_BARS),
                        )
                    }
                }
            }
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
        stopTicker()
        mic.value = MicState.Idle
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun now() = clock.now().toEpochMilliseconds()

    companion object {
        const val SAMPLE_MS = 100L

        /** How many bars the live waveform keeps. */
        const val LIVE_BARS = 40
    }
}
