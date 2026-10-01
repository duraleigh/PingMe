// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.later

import androidx.annotation.StringRes
import org.pingme.app.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.time.Instant

/** One quick choice in the Send later sheet. */
data class QuickTime(
    @param:StringRes val label: Int,
    val at: Instant,
)

/**
 * The sheet's quick chips (UI_DESIGN.md 10.13): In 1 hour, This evening (while it is still
 * before evening), Tomorrow morning, Tomorrow at this time, Next Monday morning.
 */
fun quickTimes(
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): List<QuickTime> {
    val here =
        ZonedDateTime
            .ofInstant(
                java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()),
                zone,
            ).truncatedTo(ChronoUnit.MINUTES)
    val today = here.toLocalDate()

    fun on(
        day: LocalDate,
        time: LocalTime,
    ) = Instant.fromEpochMilliseconds(ZonedDateTime.of(day, time, zone).toInstant().toEpochMilli())
    val evening = on(today, EVENING)
    return listOfNotNull(
        QuickTime(
            R.string.later_in_an_hour,
            Instant.fromEpochMilliseconds(here.plusHours(1).toInstant().toEpochMilli()),
        ),
        QuickTime(R.string.later_this_evening, evening).takeIf { here.toLocalTime() < EVENING.minusHours(1) },
        QuickTime(R.string.later_tomorrow_morning, on(today.plusDays(1), MORNING)),
        QuickTime(
            R.string.later_tomorrow_same,
            Instant.fromEpochMilliseconds(here.plusDays(1).toInstant().toEpochMilli()),
        ),
        QuickTime(R.string.later_next_monday, on(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), MORNING)),
    )
}

private val MORNING = LocalTime.of(9, 0)
private val EVENING = LocalTime.of(18, 0)
