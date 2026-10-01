// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.ui.R as UiR

/** Stands in for the header while selecting: how many, and a way out (UI_DESIGN.md 3.3). */
@Composable
fun SelectionHeader(
    count: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
            ) { Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.close_selection)) }
            Text(
                pluralStringResource(R.plurals.selected, count, count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** What to do with the selection. */
class SelectionActions(
    val onCopy: () -> Unit,
    val onForward: () -> Unit,
    val onDelete: () -> Unit,
    val onShare: () -> Unit,
)

/** Replaces the composer while selecting: Copy, Forward, Delete, Share (UI_DESIGN.md 2.2, 3.3). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SelectionToolbar(
    actions: SelectionActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(expanded = true) {
            IconButton(onClick = actions.onCopy) {
                Icon(painterResource(UiR.drawable.ic_content_copy), stringResource(R.string.action_copy))
            }
            IconButton(onClick = actions.onForward) {
                Icon(painterResource(UiR.drawable.ic_forward), stringResource(R.string.action_forward))
            }
            IconButton(onClick = actions.onDelete) {
                Icon(painterResource(UiR.drawable.ic_delete), stringResource(R.string.action_delete_message))
            }
            IconButton(onClick = actions.onShare) {
                Icon(painterResource(UiR.drawable.ic_share), stringResource(R.string.action_share))
            }
        }
    }
}
