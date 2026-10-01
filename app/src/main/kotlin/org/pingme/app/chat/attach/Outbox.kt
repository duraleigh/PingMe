// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    /** Copies picked photos, videos, or files in, in the order picked. */
    fun addPicked(uris: List<Uri>) = adding { uris.mapNotNull { files.copy(it) } }

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
