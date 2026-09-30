// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test
    fun doublesFromOneSecondAndCapsAtFiveMinutes() {
        val waits = (1..12).map { Backoff.delayFor(it) }
        assertEquals(
            listOf(1, 2, 4, 8, 16, 32, 64, 128, 256).map { it.seconds } + List(3) { 5.minutes },
            waits,
        )
        assertEquals(5.minutes, Backoff.delayFor(10_000))
    }
}
