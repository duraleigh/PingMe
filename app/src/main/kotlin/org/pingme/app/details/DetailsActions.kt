// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.pingme.core.model.ChatFolder
import org.pingme.core.ui.components.ChoiceSetting
import org.pingme.core.ui.components.SettingsSectionHeader
import org.pingme.core.ui.components.SwitchSetting
import org.pingme.core.ui.R as UiR

/** Obscure messages and Low priority (UI_DESIGN.md 10.7, 10.10). */
@Composable
fun DetailsPrivacy(
    state: ChatDetailsState,
    choices: ChatChoices,
    modifier: Modifier = Modifier,
) {
    val chat = state.chat ?: return
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.details_privacy))
        SwitchSetting(stringResource(R.string.details_obscure), chat.isObscured, choices.onObscured)
        Text(
            stringResource(R.string.details_obscure_note),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchSetting(stringResource(R.string.details_low_priority), chat.isLowPriority, choices.onLowPriority)
        Text(
            stringResource(R.string.details_low_priority_note),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The folder (Instagram's Primary and General), Archive, Block where the network allows it,
 * and Delete, each with a plain question before anything final (UI_DESIGN.md 3.4).
 */
@Composable
fun DetailsChatActions(
    state: ChatDetailsState,
    choices: ChatChoices,
    modifier: Modifier = Modifier,
) {
    val chat = state.chat ?: return
    val network =
        state.account
            ?.network
            ?.displayName
            .orEmpty()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    Column(modifier) {
        val folder = chat.folder
        if (state.capabilities?.folders == true && (folder == ChatFolder.PRIMARY || folder == ChatFolder.GENERAL)) {
            ChoiceSetting(
                stringResource(
                    R.string.details_folder,
                ),
                listOf(ChatFolder.PRIMARY, ChatFolder.GENERAL),
                folder,
                {
                    stringResource(
                        if (it ==
                            ChatFolder.PRIMARY
                        ) {
                            R.string.details_folder_primary
                        } else {
                            R.string.details_folder_general
                        },
                    )
                },
                choices.onFolder,
            )
        }
        Action(if (chat.isArchived) R.string.details_unarchive else R.string.details_archive, UiR.drawable.ic_archive) {
            choices.onArchived(!chat.isArchived)
        }
        if (state.capabilities?.block ==
            true
        ) {
            Action(R.string.details_block, UiR.drawable.ic_block, danger = true) { confirm = Confirm.BLOCK }
        }
        Action(R.string.details_delete, UiR.drawable.ic_delete, danger = true) { confirm = Confirm.DELETE }
    }
    confirm?.let { which ->
        ConfirmDialog(which, chat.nameOverride ?: chat.title, network, choices) { confirm = null }
    }
}

private enum class Confirm { BLOCK, DELETE }

// The plain question before blocking or deleting.
@Composable
private fun ConfirmDialog(
    which: Confirm,
    name: String,
    network: String,
    choices: ChatChoices,
    onDismiss: () -> Unit,
) {
    val block = which == Confirm.BLOCK
    val question =
        if (block) {
            stringResource(
                R.string.details_block_confirm,
                name,
                network,
            )
        } else {
            stringResource(R.string.details_delete_confirm, network)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(question) },
        confirmButton = {
            TextButton({
                onDismiss()
                if (block) choices.onBlock() else choices.onDelete()
            }) { Text(stringResource(if (block) R.string.details_block else R.string.details_delete)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun Action(
    label: Int,
    icon: Int,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(painterResource(icon), null) },
        colors = ListItemDefaults.colors(headlineColor = colour, leadingIconColor = colour),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
