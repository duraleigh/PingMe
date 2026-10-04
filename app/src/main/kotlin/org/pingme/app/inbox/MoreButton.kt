// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.pingme.core.model.Account
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
                    text = { Text(barLabel(entry, accounts)) },
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

// More shows a dot when anything behind it is unread.
@Composable
internal fun MoreIcon(more: List<BarEntry>) {
    BadgedBox(badge = { if (more.any { it.badge > 0 }) Badge(Modifier.size(MORE_DOT)) }) {
        Icon(painterResource(UiR.drawable.ic_apps), null)
    }
}

private val MORE_DOT = 8.dp

// Long names ("Low priority", "Google Messages") wrap to a second line (owner, 2026-10-04).
@Composable
internal fun BarLabel(text: String) {
    Text(text, maxLines = 2, textAlign = TextAlign.Center)
}
