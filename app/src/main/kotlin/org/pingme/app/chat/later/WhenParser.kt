// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.later

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.time.Instant

/**
 * Reads "when" for Send later (UI_DESIGN.md 10.13): "in 45 minutes", "in 2 hours",
 * "tomorrow 9am", "friday 6pm", "next monday", "tonight", "18:30", "dec 24 8pm". English
 * only for now. A time already past today means the same time tomorrow; a day without a
 * time means 9 in the morning. Null when the words are not understood or the time has passed.
 */
object WhenParser {
    fun parse(
        text: String,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? {
        val words =
            text
                .trim()
                .lowercase()
                .replace(Regex("\\s+"), " ")
                .removePrefix("at ")
                .removePrefix("on ")
        if (words.isEmpty()) return null
        val start = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()), zone)
        val found = relative(words, start) ?: absolute(words, start) ?: return null
        return Instant.fromEpochMilliseconds(found.toInstant().toEpochMilli()).takeIf { it > now }
    }

    // "in 45 minutes", "in an hour", "in 2 days".
    private fun relative(
        words: String,
        start: ZonedDateTime,
    ): ZonedDateTime? {
        val match = RELATIVE.matchEntire(words) ?: return null
        val amount = match.groupValues[1].let { if (it == "a" || it == "an") 1L else it.toLongOrNull() } ?: return null
        val unit =
            when (match.groupValues[2].removeSuffix("s")) {
                "min", "minute" -> ChronoUnit.MINUTES
                "hr", "hour" -> ChronoUnit.HOURS
                "day" -> ChronoUnit.DAYS
                "week" -> ChronoUnit.WEEKS
                else -> return null
            }
        return start.plus(amount, unit)
    }

    // A day ("tomorrow", "friday", "next monday", "dec 24"), a time ("6pm", "18:30", "noon"), or both.
    private fun absolute(
        words: String,
        start: ZonedDateTime,
    ): ZonedDateTime? =
        timeIn(words)?.let { (time, rest) ->
            val day = rest.takeIf { it.isNotEmpty() }?.let { day(it, start.toLocalDate()) ?: UNKNOWN }
            when {
                day == UNKNOWN -> null
                day == null && time == null -> null
                else -> at(day, time ?: MORNING, start)
            }
        }

    // A day with no date is today, or tomorrow once that time has passed.
    private fun at(
        day: LocalDate?,
        time: LocalTime,
        start: ZonedDateTime,
    ): ZonedDateTime {
        val date = day ?: start.toLocalDate().let { if (time <= start.toLocalTime()) it.plusDays(1) else it }
        return ZonedDateTime.of(LocalDateTime.of(date, time), start.zone)
    }

    // The time in [words] and what is left once it and filler words are taken out; null for an impossible time.
    private fun timeIn(words: String): Pair<LocalTime?, String>? {
        var rest = words
        var time: LocalTime? = null
        PARTS_OF_DAY.filter { rest.contains(it.first) }.forEach { (phrase, at) ->
            time = at
            rest = rest.replace(phrase, " ")
        }
        // A time needs minutes or am/pm, so the 24 in "dec 24" is not read as a time.
        val match =
            TIME.findAll(rest).firstOrNull {
                it.groupValues[MINUTES].isNotEmpty() ||
                    it.groupValues[HALF].isNotEmpty()
            }
        if (match != null) {
            time = clock(match) ?: return null
            rest = rest.replace(match.value, " ")
        }
        return time to rest.split(' ').filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")
    }

    private fun day(
        words: String,
        today: LocalDate,
    ): LocalDate? {
        // "next friday" and "friday" both mean the coming Friday, as people use them.
        val name = words.removePrefix("next ").removePrefix("this ").trim()
        return when (name) {
            "today" -> today
            "tomorrow", "tmrw", "tmr" -> today.plusDays(1)
            else -> weekday(name)?.let { today.with(TemporalAdjusters.next(it)) } ?: date(name, today)
        }
    }

    private fun weekday(name: String) =
        DayOfWeek.entries.firstOrNull { day ->
            val full = day.name.lowercase()
            name == full || (name.length >= SHORTEST_NAME && full.startsWith(name))
        }

    // "dec 24", "24 dec", "december 24th".
    private fun date(
        words: String,
        today: LocalDate,
    ): LocalDate? {
        val parts = words.replace(ORDINAL, "$1").split(' ').takeIf { it.size == 2 } ?: return null
        val number = parts.firstNotNullOfOrNull { it.toIntOrNull() }
        val month =
            parts.firstOrNull { it.toIntOrNull() == null }?.let { name ->
                MONTHS.indexOfFirst { name.startsWith(it) } +
                    1
            }
                ?: 0
        val date = number?.takeIf { month > 0 }?.let { runCatching { LocalDate.of(today.year, month, it) }.getOrNull() }
        return date?.let { if (it < today) it.plusYears(1) else it }
    }

    private fun clock(match: MatchResult): LocalTime? {
        val (hourText, minuteText, half) = match.destructured
        val hour =
            hourText.toInt().let {
                when {
                    half == "pm" && it < NOON_HOUR -> it + NOON_HOUR
                    half == "am" && it == NOON_HOUR -> 0
                    else -> it
                }
            }
        val minute = minuteText.ifEmpty { "0" }.toInt()
        return if (hour in 0..LAST_HOUR && minute in 0..LAST_MINUTE) LocalTime.of(hour, minute) else null
    }

    private const val NOON_HOUR = 12
    private const val MINUTES = 2
    private const val HALF = 3
    private const val SHORTEST_NAME = 3
    private val UNKNOWN = LocalDate.MIN
    private val ORDINAL = Regex("(\\d+)(st|nd|rd|th)")
    private const val LAST_HOUR = 23
    private const val LAST_MINUTE = 59
    private val MORNING = LocalTime.of(9, 0)
    private val RELATIVE = Regex("in (\\d+|a|an) (mins?|minutes?|hrs?|hours?|days?|weeks?)")
    private val TIME = Regex("\\b(\\d{1,2})(?::(\\d{2}))? ?(am|pm)?\\b")
    private val FILLER = setOf("at", "on", "the", "by")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val PARTS_OF_DAY =
        listOf(
            "tonight" to LocalTime.of(20, 0),
            "this evening" to LocalTime.of(18, 0),
            "evening" to LocalTime.of(18, 0),
            "morning" to LocalTime.of(9, 0),
            "afternoon" to LocalTime.of(14, 0),
            "noon" to LocalTime.of(12, 0),
            "midday" to LocalTime.of(12, 0),
        )
}
