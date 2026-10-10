// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.later

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.chat.scheduledLabel
import org.pingme.core.ui.components.PingMeSheet
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.time.Clock
import kotlin.time.Instant
import org.pingme.core.ui.R as UiR

/**
 * Send later (UI_DESIGN.md 10.13): quick chips, a date and time picker, and a field that
 * understands "friday 6pm" or "in 45 minutes". [onPick] gets the chosen moment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendLaterSheet(
    onPick: (Instant) -> Unit,
    onDismiss: () -> Unit,
    clock: Clock = Clock.System,
) {
    var words by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val now = remember { clock.now() }
    val typed = remember(words) { WhenParser.parse(words, clock.now()) }
    val choose = { at: Instant ->
        onPick(at)
        onDismiss()
    }
    PingMeSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.later_title), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickTimes(now).forEach { quick ->
                    SuggestionChip({ choose(quick.at) }, label = { Text(stringResource(quick.label)) })
                }
            }
            OutlinedButton({ picking = true }, Modifier.fillMaxWidth()) {
                Icon(painterResource(UiR.drawable.ic_schedule), null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.later_pick))
            }
            OutlinedTextField(
                words,
                { words = it },
                Modifier.fillMaxWidth().testTag(LATER_FIELD),
                placeholder = { Text(stringResource(R.string.later_type)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                supportingText = {
                    when {
                        words.isBlank() -> Unit
                        typed == null -> Text(stringResource(R.string.later_not_understood))
                        else -> Text(scheduledLabel(typed))
                    }
                },
            )
            Button({ typed?.let(choose) }, Modifier.fillMaxWidth(), enabled = typed != null) {
                Text(stringResource(R.string.later_schedule))
            }
        }
    }
    if (picking) DateThenTime({ picking = false }) { choose(it) }
}

// The date first, then the time on that date.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateThenTime(
    onDismiss: () -> Unit,
    onPick: (Instant) -> Unit,
) {
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val dates = rememberDatePickerState()
    val times = rememberTimePickerState()
    val chosen = day
    if (chosen == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton({
                    dates.selectedDateMillis?.let {
                        day =
                            java.time.Instant
                                .ofEpochMilli(it)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        ) { DatePicker(dates) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton({
                    val at = ZonedDateTime.of(chosen, LocalTime.of(times.hour, times.minute), ZoneId.systemDefault())
                    onPick(Instant.fromEpochMilliseconds(at.toInstant().toEpochMilli()))
                    onDismiss()
                }) { Text(stringResource(R.string.later_schedule)) }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
            text = { TimePicker(times) },
        )
    }
}

const val LATER_FIELD = "send-later-field"
