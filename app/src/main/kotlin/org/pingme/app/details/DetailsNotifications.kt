// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.content.IntentCompat
import org.pingme.app.R
import org.pingme.core.model.ChatOverrides
import org.pingme.core.model.VibrationPattern
import org.pingme.core.ui.components.SettingsSectionHeader
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Mute for a while, and this chat's own sound and vibration (UI_DESIGN.md 3.4, 6.1). */
@Composable
fun DetailsNotifications(
    state: ChatDetailsState,
    viewModel: NotificationChoices,
    modifier: Modifier = Modifier,
) {
    val chat = state.chat ?: return
    val overrides = state.overrides ?: ChatOverrides(chat.id)
    var asking by remember { mutableStateOf<Ask?>(null) }
    val pickers = rememberSoundPickers(overrides.soundUri) { viewModel.onNotification(it, overrides.vibration) }
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.details_notifications))
        val until = chat.muteUntil
        ListItem(
            headlineContent = { Text(stringResource(R.string.details_mute_for)) },
            supportingContent = {
                when {
                    !chat.isMuted -> Unit
                    until == null -> Text(stringResource(R.string.details_muted_forever))
                    else -> Text(stringResource(R.string.details_muted_until, shortDateTime(until)))
                }
            },
            modifier = Modifier.selectable(false, onClick = { asking = Ask.MUTE }, role = Role.Button),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.details_sound)) },
            supportingContent = { Text(stringResource(soundLabel(overrides.soundUri))) },
            modifier = Modifier.selectable(false, onClick = { asking = Ask.SOUND }, role = Role.Button),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.details_vibration)) },
            supportingContent = { Text(stringResource(vibrationLabel(overrides.vibration))) },
            modifier = Modifier.selectable(false, onClick = { asking = Ask.VIBRATION }, role = Role.Button),
        )
    }
    asking?.let { NotificationDialog(it, overrides, viewModel, pickers) { asking = null } }
}

private enum class Ask { MUTE, SOUND, VIBRATION }

// The choice dialog for whichever row was tapped.
@Composable
private fun NotificationDialog(
    ask: Ask,
    overrides: ChatOverrides,
    viewModel: NotificationChoices,
    pickers: SoundPickers,
    onDone: () -> Unit,
) {
    when (ask) {
        Ask.MUTE -> {
            Choose(
                R.string.details_mute_for,
                MUTES,
                null,
                { stringResource(it.first) },
                { viewModel.onMute(it.second) },
            ) {
                onDone()
            }
        }

        Ask.SOUND -> {
            Choose(R.string.details_sound, SOUNDS, null, { stringResource(it) }, { choice ->
                when (choice) {
                    R.string.details_sound_default -> viewModel.onNotification(null, overrides.vibration)
                    R.string.details_sound_silent -> viewModel.onNotification(ChatOverrides.SILENT, overrides.vibration)
                    R.string.details_sound_pick -> pickers.ringtone()
                    else -> pickers.file()
                }
            }) { onDone() }
        }

        Ask.VIBRATION -> {
            Choose(R.string.details_vibration, VIBRATIONS, overrides.vibration, {
                stringResource(
                    vibrationLabel(it),
                )
            }, {
                viewModel.onNotification(overrides.soundUri, it)
            }) { onDone() }
        }
    }
}

/** The system's ringtone picker, or any audio file kept with permission to read it later. */
private class SoundPickers(
    val ringtone: () -> Unit,
    val file: () -> Unit,
)

@Composable
private fun rememberSoundPickers(
    current: String?,
    onPick: (String?) -> Unit,
): SoundPickers {
    val context = LocalContext.current
    val ringtone =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri =
                    result.data?.let {
                        IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                    }
                onPick(uri?.toString() ?: ChatOverrides.SILENT)
            }
        }
    val file =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            // The sound plays long after this screen closes, so keep the right to read it.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onPick(uri.toString())
        }
    return SoundPickers(
        ringtone = {
            ringtone.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        current?.takeIf { it != ChatOverrides.SILENT }?.let(Uri::parse),
                    ),
            )
        },
        file = { file.launch(arrayOf("audio/*")) },
    )
}

/** A short list of choices in a dialog; picking one closes it. */
@Composable
private fun <T> Choose(
    @StringRes title: Int,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(label(option)) },
                        leadingContent = { RadioButton(option == selected, onClick = null) },
                        modifier =
                            Modifier.selectable(option == selected, role = Role.RadioButton, onClick = {
                                onPick(option)
                                onDismiss()
                            }),
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun soundLabel(uri: String?) =
    when (uri) {
        null -> R.string.details_sound_default
        ChatOverrides.SILENT -> R.string.details_sound_silent
        else -> R.string.details_sound_custom
    }

private fun vibrationLabel(pattern: VibrationPattern?) =
    when (pattern) {
        null -> R.string.details_vibration_default
        VibrationPattern.OFF -> R.string.details_vibration_off
        VibrationPattern.SHORT -> R.string.details_vibration_short
        VibrationPattern.LONG -> R.string.details_vibration_long
        VibrationPattern.DOUBLE -> R.string.details_vibration_double
        VibrationPattern.HEARTBEAT -> R.string.details_vibration_heartbeat
        VibrationPattern.RAPID -> R.string.details_vibration_rapid
    }

private fun shortDateTime(at: kotlin.time.Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(
            java.time.Instant
                .ofEpochMilli(at.toEpochMilliseconds())
                .atZone(ZoneId.systemDefault()),
        )

private val MUTES =
    listOf(
        R.string.details_mute_hour to 1.hours,
        R.string.details_mute_8_hours to 8.hours,
        R.string.details_mute_week to 7.days,
        R.string.details_mute_forever to null,
    )
private val SOUNDS =
    listOf(
        R.string.details_sound_default,
        R.string.details_sound_silent,
        R.string.details_sound_pick,
        R.string.details_sound_file,
    )
private val VIBRATIONS: List<VibrationPattern?> = listOf(null) + VibrationPattern.entries
