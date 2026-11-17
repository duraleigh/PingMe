// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pingme.core.connector.Diag
import org.pingme.core.connector.OutgoingAttachment

/**
 * What waits above the composer to go with the next message (UI_DESIGN.md 5.8). The text
 * typed alongside becomes the caption.
 */
class Outbox(
    private val scope: CoroutineScope,
    val files: OutgoingFiles,
) {
    private val waiting = MutableStateFlow<List<OutgoingAttachment>>(emptyList())
    private val copying = MutableStateFlow(0)

    val staged: StateFlow<List<OutgoingAttachment>> = waiting.asStateFlow()

    /** How many picks are still being copied in; send waits for them. */
    val busy: StateFlow<Int> = copying.asStateFlow()

    private val unread = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /**
     * How many of a pick could not be copied in. Each is said aloud rather than dropped in
     * silence (owner, 2026-11-02: a picture sent with text never went, and nothing said so).
     */
    val missed: SharedFlow<Int> = unread.asSharedFlow()

    /** Copies picked photos, videos, or files in, in the order picked. */
    fun addPicked(uris: List<Uri>) =
        adding {
            val copied = uris.mapNotNull { files.copy(it) }
            val lost = uris.size - copied.size
            if (lost > 0) {
                Diag.note("PingMeOutbox", "$lost of ${uris.size} picked file(s) could not be read; not attached")
                unread.tryEmit(lost)
            }
            copied
        }

    fun addContact(uri: Uri) = adding { listOfNotNull(files.contact(uri)) }

    fun addLocation(
        latitude: Double,
        longitude: Double,
    ) = adding { listOf(files.location(latitude, longitude)) }

    fun add(attachment: OutgoingAttachment) = waiting.update { it + attachment }

    fun remove(attachment: OutgoingAttachment) = waiting.update { it - attachment }

    /** Everything waiting, emptied for the next message. */
    fun take(): List<OutgoingAttachment> = waiting.getAndUpdate { emptyList() }

    private fun adding(load: suspend () -> List<OutgoingAttachment>) {
        copying.update { it + 1 }
        scope.launch {
            try {
                val loaded = load()
                waiting.update { it + loaded }
            } finally {
                copying.update { it - 1 }
            }
        }
    }
}
