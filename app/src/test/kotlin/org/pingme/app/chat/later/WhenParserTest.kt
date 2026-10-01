// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.later

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Instant

/** Send later's phrase reader (UI_DESIGN.md 10.13, BUILD_PLAN.md P4.4 "schedule parser"). */
class WhenParserTest {
    private val zone = ZoneId.of("America/New_York")

    // Wednesday 1 October 2026, 2:30 PM in New York.
    private val now = at(2026, 10, 1, 14, 30)

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ) = Instant.fromEpochMilliseconds(
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli(),
    )

    private fun read(text: String) = WhenParser.parse(text, now, zone)

    @Test
    fun countsForward() {
        assertEquals(at(2026, 10, 1, 15, 15), read("in 45 minutes"))
        assertEquals(at(2026, 10, 1, 15, 30), read("in an hour"))
        assertEquals(at(2026, 10, 3, 14, 30), read("in 2 days"))
    }

    @Test
    fun readsDaysAndTimes() {
        assertEquals(at(2026, 10, 2, 18, 0), read("friday 6pm"))
        assertEquals(at(2026, 10, 2, 18, 0), read("Friday at 6 PM"))
        assertEquals(at(2026, 10, 2, 9, 0), read("tomorrow morning"))
        assertEquals(at(2026, 10, 2, 9, 0), read("tomorrow"))
        assertEquals(at(2026, 10, 5, 9, 0), read("next monday"))
        assertEquals(at(2026, 10, 1, 20, 0), read("tonight"))
        assertEquals(at(2026, 10, 1, 18, 30), read("18:30"))
        assertEquals(at(2026, 12, 24, 20, 0), read("dec 24 8pm"))
        assertEquals(at(2026, 12, 24, 9, 0), read("24th december"))
    }

    @Test
    fun aTimeAlreadyGoneIsTomorrow() {
        assertEquals(at(2026, 10, 2, 9, 15), read("9:15am"))
    }

    @Test
    fun quickChipsLandWherePeopleExpect() {
        val chips = quickTimes(now, zone).map { it.label to it.at }
        assertEquals(
            listOf(
                org.pingme.app.R.string.later_in_an_hour to at(2026, 10, 1, 15, 30),
                org.pingme.app.R.string.later_this_evening to at(2026, 10, 1, 18, 0),
                org.pingme.app.R.string.later_tomorrow_morning to at(2026, 10, 2, 9, 0),
                org.pingme.app.R.string.later_tomorrow_same to at(2026, 10, 2, 14, 30),
                org.pingme.app.R.string.later_next_monday to at(2026, 10, 5, 9, 0),
            ),
            chips,
        )
        val late = quickTimes(at(2026, 10, 1, 17, 30), zone).map { it.label }
        assertEquals(
            "no 'this evening' once it is nearly evening",
            false,
            org.pingme.app.R.string.later_this_evening in late,
        )
    }

    @Test
    fun nonsenseAndThePastAreNotUnderstood() {
        assertNull(read("whenever"))
        assertNull(read(""))
        assertNull(read("25:00"))
        assertNull(read("today 8am"))
    }
}
