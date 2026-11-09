// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.util.Log
import org.pingme.core.model.MessageStatus
import org.pingme.core.store.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Tidies the store once per app start: stand-in copies of sent messages that were never
 * matched to the network's copy (Gate G2 ghosts; their remote ids start with "tmp/"), and
 * empty text bubbles (system notes that were once drawn as messages).
 */
@Singleton
class StoreHousekeeping
    @Inject
    constructor(
        private val messages: MessageRepository,
        private val clock: Clock,
        private val merges: org.pingme.core.store.MergeRepository,
        private val selves: org.pingme.core.store.SelfEntries,
    ) {
        suspend fun run() {
            val yours =
                selves.repair { entry ->
                    org.pingme.core.connector.Diag
                        .note(TAG, "Your number under another name, now You: $entry")
                }
            org.pingme.core.connector.Diag
                .note(TAG, "Made $yours entr(ies) with your own number You")
            // Chats left pointing at a merged chat that is gone are in no list at all (owner,
            // 2026-11-07: Merle's and Devin's Instagram chats): they become chats of their own again.
            val freed =
                merges.releaseStranded { chat ->
                    org.pingme.core.connector.Diag
                        .note(TAG, "Freeing $chat: its merged chat is gone")
                }
            if (freed > 0) {
                Log.i(TAG, "Freed $freed chats from merged chats that were gone")
                org.pingme.core.connector.Diag
                    .note(TAG, "Freed $freed chat(s) whose merged chat was gone")
            }
            val gone = messages.deleteJunk(STAND_IN_PREFIX, clock.now() - STAND_IN_AGE)
            if (gone > 0) Log.i(TAG, "Removed $gone leftover messages")
            // A send lives inside the app: one that was running when the app closed never ends,
            // and a bubble on its clock for ever has no way to be tried again (owner, 2026-11-01).
            messages.stuckSending(clock.now() - STUCK_AFTER).forEach {
                messages.updateStatus(it, MessageStatus.Failed(LEFT_BEHIND))
            }
        }

        private companion object {
            const val TAG = "PingMeHousekeeping"
            val STAND_IN_AGE = 10.minutes
            val STUCK_AFTER = 2.minutes
            const val LEFT_BEHIND = "PingMe closed before this went"
        }
    }
