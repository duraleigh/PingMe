// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.core.ui.R as UiR

/**
 * Settings > Merge chats (owner, 2026-10-03): merge any one-to-one chats you pick, or
 * review PingMe's suggestions. Nothing about merging appears in the inbox uninvited.
 */
@Composable
fun MergePage(
    onSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MergePickViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { text ->
            val shown = if (text == MergePickViewModel.MERGED) resources.getString(R.string.merge_done) else text
            android.widget.Toast
                .makeText(context, shown, android.widget.Toast.LENGTH_LONG)
                .show()
        }
    }
    Column(modifier) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_merge_pick)) },
            supportingContent = { Text(stringResource(R.string.settings_merge_pick_note)) },
            leadingContent = { Icon(painterResource(UiR.drawable.ic_call_merge), null) },
            modifier = Modifier.clickable { picking = true },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_merge_suggestions)) },
            supportingContent = {
                Text(
                    if (state.suggestions == 0) {
                        stringResource(R.string.settings_merge_none)
                    } else {
                        pluralStringResource(
                            R.plurals.settings_merge_suggestions_count,
                            state.suggestions,
                            state.suggestions,
                        )
                    },
                )
            },
            leadingContent = { Icon(painterResource(UiR.drawable.ic_groups), null) },
            modifier = Modifier.clickable(onClick = onSuggestions),
        )
    }
    if (picking) ChatPickerSheet(state.candidates, viewModel::merge) { picking = false }
}
