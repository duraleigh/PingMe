// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.inbox.NetworkBadge
import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.components.Avatar

/**
 * Picks one-to-one chats from any network to merge (owner, Phase 7): a search box, a
 * list with network badges, tick as many as you like, then Add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPickerSheet(
    chats: List<PickableChat>,
    onPick: (List<ChatId>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<ChatId>()) }
    val shown =
        remember(query, chats) {
            val q = query.trim()
            if (q.isEmpty()) chats else chats.filter { it.title.contains(q, ignoreCase = true) }
        }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.merge_pick_title),
                Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.merge_pick_hint)) },
            )
            LazyColumn(Modifier.heightIn(max = LIST_HEIGHT)) {
                items(shown, key = { it.id.value }) { chat ->
                    val on = chat.id in picked
                    ListItem(
                        onClick = { picked = if (on) picked - chat.id else picked + chat.id },
                        leadingContent = { Avatar(chat.title, size = 40.dp, photo = chat.photo) },
                        supportingContent = { NetworkBadge(chat.network) },
                        trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                    ) { Text(chat.title) }
                }
            }
            Button(
                onClick = {
                    onDismiss()
                    onPick(picked.toList())
                },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) { Text(stringResource(R.string.merge_pick_add)) }
        }
    }
}

private val LIST_HEIGHT = 420.dp
