// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.later

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.chat.scheduledLabel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Send later (UI_DESIGN.md 10.13), in as few steps as the owner asked (2026-11-07): holding Send opens
 * the calendar at once; OK goes on to the clock, whose Schedule sets the message. "Last used", at the
 * calendar's bottom left, offers the moment the last message anywhere was scheduled for, shown once to
 * confirm (Cancel, Go back, Schedule), so a second message can follow the first at the same time. It
 * is greyed out when nothing has been scheduled yet or that moment has passed.
 */
@Composable
internal fun SendLaterPicker(
    lastUsed: Instant?,
    onPick: (Instant) -> Unit,
    onDismiss: () -> Unit,
    clock: Clock = Clock.System,
) {
    var step by rememberSaveable { mutableStateOf(Step.DATE) }
    var day by rememberSaveable { mutableStateOf<Long?>(null) }
    val now = remember { clock.now() }
    val reusable = lastUsed?.takeIf { it > now }
    val choose = { at: Instant ->
        onPick(at)
        onDismiss()
    }
    val chosenDay = day
    when {
        step == Step.TIME && chosenDay != null -> {
            TimeStep(LocalDate.ofEpochDay(chosenDay), choose, onDismiss)
        }

        step == Step.LAST_USED && reusable != null -> {
            LastUsedStep(reusable, { choose(reusable) }, onBack = { step = Step.DATE }, onDismiss)
        }

        else -> {
            DateStep(
                canReuse = reusable != null,
                onReuseLast = { step = Step.LAST_USED },
                onDay = {
                    day = it.toEpochDay()
                    step = Step.TIME
                },
                onDismiss = onDismiss,
            )
        }
    }
}

private enum class Step { DATE, TIME, LAST_USED }

// The calendar, today onward: Last used at the bottom left, Cancel and OK at the right.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateStep(
    canReuse: Boolean,
    onReuseLast: () -> Unit,
    onDay: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val dates =
        rememberDatePickerState(
            initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = !utcDay(utcTimeMillis).isBefore(today)

                    override fun isSelectableYear(year: Int) = year >= today.year
                },
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                TextButton(onReuseLast, enabled = canReuse) { Text(stringResource(R.string.later_last_used)) }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) }
                TextButton({ dates.selectedDateMillis?.let { onDay(utcDay(it)) } }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        },
    ) { DatePicker(dates) }
}

// The time on the chosen day, as before.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeStep(
    day: LocalDate,
    onPick: (Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val times = rememberTimePickerState()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                val at = ZonedDateTime.of(day, LocalTime.of(times.hour, times.minute), ZoneId.systemDefault())
                onPick(Instant.fromEpochMilliseconds(at.toInstant().toEpochMilli()))
            }) { Text(stringResource(R.string.later_schedule)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        text = { TimePicker(times) },
    )
}

// The last-used moment, shown once to confirm before it is set.
@Composable
private fun LastUsedStep(
    at: Instant,
    onSchedule: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.later_last_used)) },
        text = { Text(scheduledLabel(at)) },
        confirmButton = { TextButton(onSchedule) { Text(stringResource(R.string.later_schedule)) } },
        dismissButton = {
            Row {
                TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) }
                TextButton(onBack) { Text(stringResource(R.string.later_go_back)) }
            }
        },
    )
}

private fun utcDay(utcMillis: Long): LocalDate =
    java.time.Instant
        .ofEpochMilli(utcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
