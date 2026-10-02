// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.util.Log
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
    ) {
        suspend fun run() {
            val gone = messages.deleteJunk(STAND_IN_PREFIX, clock.now() - STAND_IN_AGE)
            if (gone > 0) Log.i(TAG, "Removed $gone leftover messages")
        }

        private companion object {
            const val TAG = "PingMeHousekeeping"
            const val STAND_IN_PREFIX = "tmp/"
            val STAND_IN_AGE = 10.minutes
        }
    }
