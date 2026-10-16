// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import org.pingme.core.model.Account
import org.pingme.core.model.Transport
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

/**
 * The fixed More button: a menu of every filter and space the bar does not hold, each with
 * its unread dot; picking one opens the inbox on it, and the button reads as selected while
 * such a choice is showing.
 */
@Composable
internal fun MoreButton(
    more: List<BarEntry>,
    selected: InboxBarItem?,
    accounts: List<Account>,
    actions: BarActions,
    button: @Composable (open: () -> Unit, inMore: Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val inMore = more.any { it.item == selected }
    Box(propagateMinConstraints = true) {
        button({ open = true }, inMore)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            more.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(barLabel(entry, accounts), color = unreadColour(entry)) },
                    leadingIcon = { BarIcon(entry) },
                    trailingIcon = {
                        if (entry.item == selected) Icon(painterResource(UiR.drawable.ic_check), null)
                    },
                    onClick = {
                        open = false
                        actions.onSelect(entry.item)
                    },
                )
            }
        }
    }
}

// Unread behind More is said by its label's colour (owner, 2026-10-05), not a dot.
@Composable
internal fun MoreIcon() {
    Icon(painterResource(UiR.drawable.ic_apps), null)
}

/**
 * The colour a bar label takes while its list holds unread messages (owner, 2026-10-05):
 * a network's label in that network's bubble colour from Appearance, and any other list
 * (All, Unread, a space, Low priority) in the theme's primary colour. Unspecified when
 * nothing is unread, so the label reads in the bar's usual colour.
 */
@Composable
internal fun unreadColour(entry: BarEntry): Color {
    if (entry.badge <= 0) return Color.Unspecified
    val item = entry.item
    return if (item is InboxBarItem.Network) {
        PingMeTheme.networkColors.outgoing(item.network, Transport.NETWORK).container
    } else {
        MaterialTheme.colorScheme.primary
    }
}

/** More reads in the theme's colour while anything behind it is unread (owner, 2026-10-05). */
@Composable
internal fun moreColour(more: List<BarEntry>): Color =
    if (more.any { it.badge > 0 }) MaterialTheme.colorScheme.primary else Color.Unspecified

// Long names ("Low priority", "Google Messages") wrap to a second line (owner, 2026-10-04).
@Composable
internal fun BarLabel(
    text: String,
    colour: Color = Color.Unspecified,
) {
    Text(text, color = colour, maxLines = 2, textAlign = TextAlign.Center)
}
