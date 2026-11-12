// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.core.connector.Diag
import java.io.File
import kotlin.time.Clock

/** Where a dictation is: nothing, the microphone open, the words on their way, or what went wrong. */
sealed interface DictationState {
    data object Idle : DictationState

    data object Listening : DictationState

    data object Transcribing : DictationState

    /** The microphone gave nothing to send: another app may be using it. */
    data object NothingHeard : DictationState

    data class Failed(
        val reason: String,
    ) : DictationState
}

/**
 * Dictation into the message box (owner, 2026-10-06; UI_DESIGN.md 5.6): volume down held in a
 * chat records with the phone's microphone, and on release the recording goes to Groq with
 * the owner's key; the words come back through [results] for the composer to put at the
 * cursor. Nothing is sent anywhere until the key is let go.
 */
class Dictation(
    private val scope: CoroutineScope,
    private val recorder: VoiceRecorder,
    private val clock: Clock,
    private val key: () -> String,
    private val transcribe: suspend (File, String) -> String,
    /** The owner's switch for volume down (Settings, Voice notes); off leaves the key as volume. */
    private val enabled: () -> Boolean = { true },
) {
    private val current = MutableStateFlow<DictationState>(DictationState.Idle)
    private val words = MutableSharedFlow<String>(extraBufferCapacity = BUFFER)

    val state: StateFlow<DictationState> = current.asStateFlow()

    /** Each finished dictation's text, once. */
    val results: SharedFlow<String> = words.asSharedFlow()

    /** True when the switch is on and there is a key to send the audio with; otherwise volume down stays volume. */
    val ready: Boolean get() = enabled() && key().isNotBlank()

    /** Opens the microphone; false when it would not open or a dictation is still being written out. */
    fun start(): Boolean {
        val busy = current.value is DictationState.Listening || current.value is DictationState.Transcribing
        if (busy) return false
        if (!recorder.start(now())) {
            Diag.note(TAG, "The microphone would not open for dictation")
            return false
        }
        current.value = DictationState.Listening
        return true
    }

    /** The key was let go: send the recording out and wait for the words. False when it was too short. */
    fun stop(): Boolean {
        if (current.value != DictationState.Listening) return false
        val made = recorder.finish(now())
        if (made == null) {
            Diag.note(TAG, "Dictation ended with nothing to send")
            current.value = DictationState.NothingHeard
            return false
        }
        current.value = DictationState.Transcribing
        scope.launch {
            try {
                val text = transcribe(made.file, key())
                Diag.note(TAG, "Dictation of ${made.durationMs} ms came back as ${text.length} characters")
                if (text.isNotBlank()) words.emit(text)
                current.value = DictationState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Diag.note(TAG, "Dictation failed: $e")
                current.value = DictationState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                made.file.parentFile?.deleteRecursively()
            }
        }
        return true
    }

    /** A short press: nothing was meant; the recording is thrown away. */
    fun cancel() {
        if (current.value == DictationState.Listening) {
            recorder.cancel()
            current.value = DictationState.Idle
        }
    }

    private fun now() = clock.now().toEpochMilliseconds()

    private companion object {
        const val BUFFER = 4
        const val TAG = "PingMeDictation"
    }
}

/**
 * The composer's dictation line: what is happening, and the words put at the cursor when they
 * arrive (with a space before them when the box already has text).
 */
@Composable
fun DictationRow(
    dictation: Dictation,
    field: TextFieldState,
    modifier: Modifier = Modifier,
) {
    val state by dictation.state.collectAsStateWithLifecycle()
    LaunchedEffect(dictation) {
        dictation.results.collect { text ->
            field.edit {
                val at = selection.end
                val gap = if (at > 0 && !this.asCharSequence()[at - 1].isWhitespace()) " " else ""
                insert(at, gap + text)
            }
        }
    }
    val line =
        when (val s = state) {
            DictationState.Idle -> {
                return
            }

            DictationState.Listening -> {
                stringResource(R.string.dictation_listening)
            }

            DictationState.Transcribing -> {
                stringResource(R.string.dictation_writing)
            }

            DictationState.NothingHeard -> {
                stringResource(
                    R.string.dictation_failed,
                    stringResource(R.string.dictation_nothing_heard),
                )
            }

            is DictationState.Failed -> {
                stringResource(R.string.dictation_failed, s.reason)
            }
        }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            line,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (state is DictationState.Failed || state is DictationState.NothingHeard) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
        )
    }
}
