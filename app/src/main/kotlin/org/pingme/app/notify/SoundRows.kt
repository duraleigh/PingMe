// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.notify

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

/**
 * The Sound and Vibration rows (UI_DESIGN.md 6.1), shared by a chat's details, each network,
 * each Instagram folder, and each keyword. Sound is the default, silent, a notification
 * sound from the system picker, or any audio file.
 */
@Composable
fun SoundRows(
    sound: String?,
    vibration: VibrationPattern?,
    onChange: (sound: String?, vibration: VibrationPattern?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var asking by remember { mutableStateOf<Ask?>(null) }
    val pickers = rememberSoundPickers(sound) { onChange(it, vibration) }
    val context = LocalContext.current
    Column(modifier) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.details_sound)) },
            supportingContent = { Text(stringResource(soundLabel(sound))) },
            modifier = Modifier.selectable(false, onClick = { asking = Ask.SOUND }, role = Role.Button),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.details_vibration)) },
            supportingContent = { Text(stringResource(vibrationLabel(vibration))) },
            modifier = Modifier.selectable(false, onClick = { asking = Ask.VIBRATION }, role = Role.Button),
        )
    }
    when (asking) {
        Ask.SOUND -> {
            Choose(R.string.details_sound, SOUNDS, null, { stringResource(it) }, { choice ->
                when (choice) {
                    R.string.details_sound_default -> onChange(null, vibration)
                    R.string.details_sound_silent -> onChange(ChatOverrides.SILENT, vibration)
                    R.string.details_sound_pick -> pickers.ringtone()
                    else -> pickers.file()
                }
            }) { asking = null }
        }

        Ask.VIBRATION -> {
            Choose(R.string.details_vibration, VIBRATIONS, vibration, { stringResource(vibrationLabel(it)) }, {
                onChange(sound, it)
                // Play the pattern once, so the pick is felt at once rather than at the next
                // message (owner, 2026-10-05: "none of the per-chat haptics do anything").
                preview(context, it)
            }) { asking = null }
        }

        null -> {
            Unit
        }
    }
}

private enum class Ask { SOUND, VIBRATION }

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
fun <T> Choose(
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

private val SOUNDS =
    listOf(
        R.string.details_sound_default,
        R.string.details_sound_silent,
        R.string.details_sound_pick,
        R.string.details_sound_file,
    )
private val VIBRATIONS: List<VibrationPattern?> = listOf(null) + VibrationPattern.entries

/** Plays a vibration pattern once on the phone's vibrator; the default pattern plays as Short. */
private fun preview(
    context: android.content.Context,
    pattern: VibrationPattern?,
) {
    val timings = (pattern ?: VibrationPattern.SHORT).timings
    if (timings.isEmpty()) return
    val vibrator =
        org.pingme.core.ui.components.HapticPlayer
            .vibratorOf(context) ?: return
    runCatching { vibrator.vibrate(android.os.VibrationEffect.createWaveform(timings, -1)) }
}
