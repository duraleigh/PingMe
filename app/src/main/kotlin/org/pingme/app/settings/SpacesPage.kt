// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.inbox.EditBarSheet
import org.pingme.app.inbox.drawable
import org.pingme.app.inbox.label
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceIcon
import org.pingme.core.model.SpaceId
import org.pingme.core.model.SpaceKind
import org.pingme.core.ui.components.SettingsSectionHeader
import org.pingme.core.ui.components.SwitchSetting
import java.util.UUID
import org.pingme.core.ui.R as UiR

/**
 * Settings > Spaces and the bottom bar (UI_DESIGN.md 10.4): the spaces from the networks,
 * the user's own spaces made from any chats, and which buttons the bottom bar shows.
 */
@Composable
fun SpacesPage(
    state: SettingsState,
    actions: SpaceActions,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Space?>(null) }
    var adding by remember { mutableStateOf(false) }
    var barOpen by remember { mutableStateOf(false) }
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.spaces_title))
        Text(
            stringResource(R.string.spaces_note),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.spaces.isEmpty()) Text(stringResource(R.string.spaces_none), Modifier.padding(16.dp))
        state.spaces.forEach { space -> SpaceRow(space, { editing = space }, { actions.deleteSpace(space) }) }
        val add = stringResource(R.string.spaces_new)
        AssistChip({ adding = true }, { Text(add) }, Modifier.padding(horizontal = 16.dp))
        SettingsSectionHeader(stringResource(R.string.spaces_bar))
        ListItem(
            headlineContent = { Text(stringResource(R.string.spaces_bar_edit)) },
            supportingContent = { Text(stringResource(R.string.spaces_bar_note)) },
            leadingContent = { Icon(painterResource(UiR.drawable.ic_edit), null) },
            modifier = Modifier.clickable { barOpen = true },
        )
    }
    if (adding || editing != null) {
        SpaceDialog(editing, state, actions::saveSpace) {
            adding = false
            editing = null
        }
    }
    if (barOpen) EditBarSheet(state.bar, state.accounts, state.spaces, actions::setBar) { barOpen = false }
}

// One space: its kind and size; the user's own spaces can be changed or deleted.
@Composable
private fun SpaceRow(
    space: Space,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val custom = space.kind == SpaceKind.CUSTOM
    ListItem(
        headlineContent = { Text(space.title) },
        leadingContent = { Icon(painterResource(space.icon.drawable()), null) },
        supportingContent = {
            Text(
                pluralStringResource(
                    R.plurals.spaces_count,
                    space.chatIds.size,
                    stringResource(kindLabel(space.kind)),
                    space.chatIds.size,
                ),
            )
        },
        trailingContent = {
            if (custom) {
                IconButton(onDelete) {
                    Icon(painterResource(UiR.drawable.ic_delete), stringResource(R.string.spaces_delete, space.title))
                }
            }
        },
        modifier = if (custom) Modifier.clickable(onClick = onEdit) else Modifier,
    )
}

/** Makes or changes the user's own space: a name and any chats, from any account. */
@Composable
private fun SpaceDialog(
    space: Space?,
    state: SettingsState,
    onSave: (Space) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(space) {
        mutableStateOf(space ?: Space(SpaceId("custom-${UUID.randomUUID()}"), null, "", SpaceKind.CUSTOM, emptyList()))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (space == null) R.string.spaces_new else R.string.spaces_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    draft.title,
                    { draft = draft.copy(title = it) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.spaces_name)) },
                    singleLine = true,
                )
                SettingsSectionHeader(stringResource(R.string.spaces_icon))
                IconPicker(draft.icon) { draft = draft.copy(icon = it) }
                SwitchSetting(
                    stringResource(R.string.spaces_show_in_all),
                    draft.showInAll,
                    { draft = draft.copy(showInAll = it) },
                    description = stringResource(R.string.spaces_show_in_all_note),
                )
                SettingsSectionHeader(stringResource(R.string.spaces_chats))
                state.chats.forEach { chat ->
                    Tick(chat.nameOverride ?: chat.title, chat.id in draft.chatIds) { on ->
                        draft = draft.copy(chatIds = if (on) draft.chatIds + chat.id else draft.chatIds - chat.id)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                onSave(draft.copy(title = draft.title.trim()))
                onDismiss()
            }, enabled = draft.title.isNotBlank()) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** The icon a space shows in the bottom bar and the avatar menu (UI_DESIGN.md 10.4). */
@Composable
private fun IconPicker(
    chosen: SpaceIcon,
    onPick: (SpaceIcon) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SpaceIcon.entries.forEach { icon ->
            FilledIconToggleButton(icon == chosen, { onPick(icon) }) {
                Icon(painterResource(icon.drawable()), stringResource(icon.label()))
            }
        }
    }
}

private fun kindLabel(kind: SpaceKind) =
    when (kind) {
        SpaceKind.WHATSAPP_COMMUNITY -> R.string.spaces_kind_whatsapp
        SpaceKind.TELEGRAM_FORUM -> R.string.spaces_kind_telegram
        SpaceKind.CUSTOM -> R.string.spaces_kind_custom
    }
