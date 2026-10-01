// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The chat's voice-note transcripts, each written out the first time its bubble asks. */
class Transcripts(
    private val transcriber: VoiceTranscriber,
    private val scope: CoroutineScope,
) {
    private val notes = ConcurrentHashMap<String, MutableStateFlow<Transcript>>()

    fun of(
        key: String,
        path: String,
    ): StateFlow<Transcript> =
        notes.getOrPut(key) {
            MutableStateFlow<Transcript>(Transcript.Working).also { note ->
                scope.launch { note.value = transcriber.transcript(key, File(path)) }
            }
        }
}
