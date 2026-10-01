// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.appearance.ColorPickerSheet
import org.pingme.app.inbox.displayName
import org.pingme.app.setup.NetworkChoices
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.ui.components.ChoiceSetting
import org.pingme.core.ui.components.ColorSwatch
import org.pingme.core.ui.components.SwitchSetting

/** Every account with its network and connection (UI_DESIGN.md 6.5). */
@Composable
fun AccountList(
    state: SettingsState,
    networks: List<NetworkId>,
    onOpen: (AccountId) -> Unit,
    onAdd: (NetworkId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf(false) }
    Column(modifier) {
        if (state.accounts.isEmpty()) Text(stringResource(R.string.account_none), Modifier.padding(16.dp))
        state.accounts.forEach { account ->
            ListItem(
                headlineContent = { Text(account.displayName) },
                supportingContent = { Text("${account.network.displayName} · ${stateLabel(account.state)}") },
                leadingContent = {
                    ColorSwatch(
                        Color(
                            account.colorArgb,
                        ),
                        selected = false,
                        description = account.displayName,
                        onClick = {
                            onOpen(account.id)
                        },
                    )
                },
                modifier = Modifier.clickable { onOpen(account.id) },
            )
        }
        val add = stringResource(R.string.accounts_add)
        AssistChip({ adding = true }, { Text(add) }, Modifier.padding(horizontal = 16.dp))
    }
    // Which network to add, each with its risk, as in setup (DESIGN.md 7).
    if (adding) {
        ModalBottomSheet({ adding = false }) {
            NetworkChoices(networks, {
                adding = false
                onAdd(it)
            }, Modifier.padding(bottom = 24.dp))
        }
    }
}

/**
 * One account's own settings (UI_DESIGN.md 6.5): its name, badge colour, notifications,
 * whether it shows in the inbox, and its connection; Instagram adds Show General (6.4).
 */
@Composable
fun AccountPage(
    state: SettingsState,
    id: AccountId,
    actions: SettingsActions,
    onLogInAgain: (Account) -> Unit,
    modifier: Modifier = Modifier,
) {
    val account = state.accounts.firstOrNull { it.id == id } ?: return
    var renaming by remember { mutableStateOf(false) }
    var colouring by remember { mutableStateOf(false) }
    val change = { f: (Account) -> Account -> actions.updateAccount(id, f) }
    Column(modifier) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.account_name)) },
            supportingContent = { Text(account.displayName) },
            modifier = Modifier.clickable { renaming = true },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.account_colour)) },
            trailingContent = {
                val colour = stringResource(R.string.account_colour)
                ColorSwatch(Color(account.colorArgb), false, colour, { colouring = true })
            },
            modifier = Modifier.clickable { colouring = true },
        )
        val modes = NotificationMode.entries
        ChoiceSetting(stringResource(R.string.account_notifications), modes, account.notificationMode, {
            stringResource(it.label())
        }, { mode -> change { it.copy(notificationMode = mode) } })
        SwitchSetting(
            stringResource(R.string.account_show_in_inbox),
            account.showInInbox,
            { on -> change { it.copy(showInInbox = on) } },
            description = stringResource(R.string.account_show_in_inbox_note),
        )
        if (account.network == NetworkId.INSTAGRAM) {
            SwitchSetting(stringResource(R.string.account_show_general), state.showGeneral, actions::setShowGeneral)
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.account_state)) },
            supportingContent = { Text(stateLabel(account.state)) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.account_log_in_again)) },
            supportingContent = { Text(stringResource(R.string.account_log_in_again_note)) },
            modifier = Modifier.clickable { onLogInAgain(account) },
        )
    }
    if (renaming) RenameAccount(account, { name -> change { it.copy(displayName = name) } }) { renaming = false }
    if (colouring) {
        val pick = { colour: Int -> change { it.copy(colorArgb = colour) } }
        ColorPickerSheet(stringResource(R.string.account_colour), account.colorArgb, pick, { colouring = false })
    }
}

@Composable
private fun RenameAccount(
    account: Account,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(account.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_name)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = {
            TextButton({
                text.trim().takeIf { it.isNotEmpty() }?.let(onRename)
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** On, Silent, Off. */
fun NotificationMode.label() =
    when (this) {
        NotificationMode.NORMAL -> R.string.mode_normal
        NotificationMode.SILENT -> R.string.mode_silent
        NotificationMode.OFF -> R.string.mode_off
    }

/** How an account's connection reads in plain words (DESIGN.md 5.3). */
@Composable
fun stateLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.Connected -> stringResource(R.string.status_connected)
        is ConnectionState.Reconnecting -> stringResource(R.string.account_reconnecting)
        is ConnectionState.ActionNeeded -> state.reason
        ConnectionState.Disabled -> stringResource(R.string.account_disabled)
        is ConnectionState.Polled -> stringResource(R.string.account_polled)
    }
