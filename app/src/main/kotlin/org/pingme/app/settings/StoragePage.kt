// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.model.MediaSettings
import org.pingme.core.ui.components.SettingsSectionHeader
import org.pingme.core.ui.components.SwitchSetting

/**
 * Settings > Media and storage (UI_DESIGN.md 5.5, 5.6, 10.16): GIF search and autoplay,
 * voice-note transcription, and saving all incoming media to app storage or a chosen folder.
 */
@Composable
fun StoragePage(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val media = state.app.media
    val change = { f: (MediaSettings) -> MediaSettings -> actions.update { it.copy(media = f(it.media)) } }
    val context = LocalContext.current
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            // Media is saved long after this screen closes, so keep the right to write there.
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            change { it.copy(saveFolderUri = uri.toString()) }
        }
    val pick = stringResource(R.string.storage_folder_pick)
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.gif))
        SwitchSetting(stringResource(R.string.storage_gif_search), media.gifSearch, { on ->
            change { it.copy(gifSearch = on) }
        }, description = stringResource(R.string.storage_gif_search_note))
        SwitchSetting(stringResource(R.string.storage_gif_autoplay), media.gifsAutoplay, { on ->
            change { it.copy(gifsAutoplay = on) }
        }, description = stringResource(R.string.storage_gif_autoplay_note))
        SettingsSectionHeader(stringResource(R.string.storage_voice))
        SwitchSetting(stringResource(R.string.storage_transcribe), media.transcribeVoice, { on ->
            change { it.copy(transcribeVoice = on) }
        }, description = stringResource(R.string.storage_transcribe_note))
        GroqKeyField(media.groqKey) { typed -> change { it.copy(groqKey = typed) } }
        SettingsSectionHeader(stringResource(R.string.settings_storage))
        SwitchSetting(stringResource(R.string.storage_save_all), media.saveAllMedia, { on ->
            change { it.copy(saveAllMedia = on) }
        }, description = stringResource(R.string.storage_save_all_note))
        ListItem(
            headlineContent = { Text(stringResource(R.string.storage_folder)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (media.saveFolderUri ==
                            null
                        ) {
                            R.string.storage_folder_app
                        } else {
                            R.string.storage_folder_chosen
                        },
                    ),
                )
            },
            trailingContent = {
                if (media.saveFolderUri != null) {
                    TextButton({ change { it.copy(saveFolderUri = null) } }) {
                        Text(stringResource(R.string.storage_folder_reset))
                    }
                }
            },
            modifier = Modifier.clickable(onClickLabel = pick) { folder.launch(null) },
        )
    }
}

/** The owner's Groq key for dictation by volume down (UI_DESIGN.md 5.6); kept only on this phone. */
@Composable
private fun GroqKeyField(
    key: String,
    onChange: (String) -> Unit,
) {
    androidx.compose.material3.OutlinedTextField(
        value = key,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.storage_dictation_key)) },
        supportingText = { Text(stringResource(R.string.storage_dictation_note)) },
        singleLine = true,
        modifier =
            androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
