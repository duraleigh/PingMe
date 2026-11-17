// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.pingme.app.chat.flickToStep
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.Transport
import org.pingme.core.ui.components.readableOn
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

/**
 * The network a new chat starts on, one pill at a time in that network's own colour, beside "On"
 * (owner, 2026-11-02: the row of pills took the top of the screen). Flick it any way to move
 * through the networks, as with the chip in a chat's box; tap it for the list. Google Messages
 * comes first, so it is where the pill starts.
 */
@Composable
internal fun NetworkPill(
    accounts: List<Account>,
    picked: AccountId?,
    onPick: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = accounts.firstOrNull { it.id == picked } ?: accounts.firstOrNull() ?: return
    var open by remember { mutableStateOf(false) }
    val fill =
        PingMeTheme.networkColors
            .outgoing(current.network, Transport.NETWORK)
            .container
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = fill,
            contentColor = readableOn(fill),
            modifier =
                Modifier
                    .testTag(NEW_CHAT_NETWORK)
                    .flickToStep(accounts.size > 1, accounts to current.id) { towardsNext ->
                        val at = accounts.indexOf(current)
                        val step = if (towardsNext) 1 else accounts.size - 1
                        onPick(accounts[(at + step) % accounts.size].id)
                    },
        ) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(current.network.lineIcon()), null, Modifier.size(20.dp))
                Text(
                    pillLabel(current, accounts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (accounts.size > 1) Icon(painterResource(UiR.drawable.ic_expand_more), null, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(pillLabel(account, accounts)) },
                    leadingIcon = { Icon(painterResource(account.network.lineIcon()), null) },
                    trailingIcon = {
                        if (account.id == current.id) Icon(painterResource(UiR.drawable.ic_check), null)
                    },
                    onClick = {
                        open = false
                        onPick(account.id)
                    },
                )
            }
        }
    }
}

// The network's name, not the account's (a phone number says nothing about where it goes); the
// account's own name only when two accounts share a network.
private fun pillLabel(
    account: Account,
    accounts: List<Account>,
): String {
    val twins = accounts.count { it.network == account.network } > 1
    return account.network.displayName + if (twins) " · ${account.displayName}" else ""
}

const val NEW_CHAT_NETWORK = "new-chat-network"
