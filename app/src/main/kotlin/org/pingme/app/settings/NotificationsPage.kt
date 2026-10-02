// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.inbox.displayName
import org.pingme.app.notify.SoundRows
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.NotificationProfile
import org.pingme.core.model.NotificationSettings
import org.pingme.core.ui.components.ChoiceSetting
import org.pingme.core.ui.components.SettingsSectionHeader
import org.pingme.core.ui.components.SwitchSetting
import org.pingme.core.ui.R as UiR

/**
 * Settings > Notifications (UI_DESIGN.md 6.1, 6.4, 10.6, 10.9): each network's on, silent,
 * or off with its sound and vibration; Instagram's three folders; keywords; and auto-copy
 * of one-time codes.
 */
@Composable
fun NotificationsPage(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val notify = state.app.notifications
    val change = { f: (NotificationSettings) -> NotificationSettings ->
        actions.update { it.copy(notifications = f(it.notifications)) }
    }
    val networks = state.accounts.map { it.network }.distinct()
    Column(modifier) {
        // A reminder only, no button into Google's settings (owner, 2026-10-02; UI_DESIGN.md 6.3).
        if (NetworkId.GMESSAGES in networks) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.notify_gm_title)) },
                supportingContent = { Text(stringResource(R.string.notify_gm_note)) },
            )
        }
        if (networks.isNotEmpty()) SettingsSectionHeader(stringResource(R.string.notify_networks))
        networks.forEach { network ->
            ProfileRows(network.displayName, notify.network(network)) { profile ->
                change { it.copy(networks = it.networks + (network to profile)) }
            }
        }
        if (NetworkId.INSTAGRAM in networks) {
            SettingsSectionHeader(stringResource(R.string.notify_folders))
            FOLDERS.forEach { (folder, label) ->
                ProfileRows(stringResource(label), notify.folder(folder)) { profile ->
                    change { it.copy(folders = it.folders + (folder to profile)) }
                }
            }
        }
        Keywords(state, actions)
        SettingsSectionHeader(stringResource(R.string.settings_notifications))
        SwitchSetting(
            stringResource(R.string.notify_auto_copy),
            notify.autoCopyCodes,
            { on -> change { it.copy(autoCopyCodes = on) } },
            description = stringResource(R.string.notify_auto_copy_note),
        )
    }
}

/** On, silent, or off, then sound and vibration while it is not off (UI_DESIGN.md 6.1). */
@Composable
private fun ProfileRows(
    title: String,
    profile: NotificationProfile,
    onChange: (NotificationProfile) -> Unit,
) {
    Column {
        ChoiceSetting(title, NotificationMode.entries, profile.mode, { stringResource(it.label()) }, { mode ->
            onChange(profile.copy(mode = mode))
        })
        if (profile.mode != NotificationMode.OFF) {
            SoundRows(
                profile.soundUri,
                profile.vibration,
                { sound, vibration -> onChange(profile.withSound(sound, vibration)) },
            )
        }
    }
}

/** A new sound needs a new channel, since Android cannot change a channel's sound (UI_DESIGN.md 6.1). */
internal fun NotificationProfile.withSound(
    sound: String?,
    vibration: org.pingme.core.model.VibrationPattern?,
) = copy(
    soundUri = sound,
    vibration = vibration,
    channelVersion = if (sound != soundUri || vibration != this.vibration) channelVersion + 1 else channelVersion,
)

// The keyword list: tap one to change it, or delete it; Add opens an empty one.
@Composable
private fun Keywords(
    state: SettingsState,
    actions: SettingsActions,
) {
    var editing by remember { mutableStateOf<KeywordRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column {
        SettingsSectionHeader(stringResource(R.string.notify_keywords))
        Text(
            stringResource(R.string.notify_keywords_note),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        state.keywords.forEach { rule ->
            ListItem(
                headlineContent = { Text(rule.pattern) },
                supportingContent = { Text(keywordSummary(rule, state)) },
                trailingContent = {
                    IconButton({ actions.deleteKeyword(rule) }) {
                        Icon(
                            painterResource(UiR.drawable.ic_delete),
                            stringResource(R.string.notify_keyword_delete, rule.pattern),
                        )
                    }
                },
                modifier = Modifier.clickable { editing = rule },
            )
        }
        val add = stringResource(R.string.notify_keyword_add)
        AssistChip({ adding = true }, { Text(add) }, Modifier.padding(horizontal = 16.dp))
    }
    if (adding || editing != null) {
        val rule = editing
        KeywordDialog(
            rule,
            rule?.let { state.app.notifications.keywords[it.id.value] } ?: NotificationProfile(),
            state,
            onSave = actions::saveKeyword,
        ) {
            adding = false
            editing = null
        }
    }
}

private val FOLDERS =
    listOf(
        ChatFolder.PRIMARY to R.string.details_folder_primary,
        ChatFolder.GENERAL to R.string.details_folder_general,
        ChatFolder.REQUESTS to R.string.notify_folder_requests,
    )
