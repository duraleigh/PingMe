// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How long to wait before a reconnect attempt, counting attempts from 1. */
fun interface RetryDelays {
    fun delayFor(attempt: Int): Duration
}

/** Reconnect waits: 1 s, 2 s, 4 s, ... doubling, capped at 5 minutes (BUILD_PLAN.md P1.4). */
object Backoff : RetryDelays {
    val FIRST = 1.seconds
    val CAP = 5.minutes

    /** The wait before retry number [attempt], counting from 1. */
    override fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "Attempts count from 1" }
        // 2^9 s is already past the cap, so larger shifts are never needed.
        val doublings = (attempt - 1).coerceAtMost(MAX_DOUBLINGS)
        return (FIRST * (1 shl doublings)).coerceAtMost(CAP)
    }

    private const val MAX_DOUBLINGS = 9
}
