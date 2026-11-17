// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test
    fun doublesFromOneSecondAndCapsAtAMinute() {
        val waits = (1..9).map { Backoff.delayFor(it) }
        assertEquals(
            listOf(1, 2, 4, 8, 16, 32).map { it.seconds } + List(3) { 1.minutes },
            waits,
        )
        assertEquals(1.minutes, Backoff.delayFor(10_000))
    }
}
