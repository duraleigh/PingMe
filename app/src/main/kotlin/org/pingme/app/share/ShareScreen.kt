// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.app.inbox.NetworkBadge
import org.pingme.core.model.ChatId
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.R as UiR

/** The share picker's screen with its view model (owner, Gate G3). */
@Composable
fun ShareRoute(
    onBack: () -> Unit,
    onOpenChat: (ChatId) -> Unit,
    onDone: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.share_failed)
    val openChat by rememberUpdatedState(onOpenChat)
    val done by rememberUpdatedState(onDone)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) {
        viewModel.finished.collect { chat -> if (chat != null) openChat(chat) else done() }
    }
    LaunchedEffect(viewModel) {
        viewModel.failed.collect { reason -> snackbar.showSnackbar(reason?.let { "$failed: $it" } ?: failed) }
    }
    if (state.empty) {
        LaunchedEffect(Unit) { back() }
        return
    }
    ShareScreen(state, snackbar, onBack, viewModel::search, viewModel::note, viewModel::toggle, viewModel::send)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ShareScreen(
    state: ShareUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onNote: (String) -> Unit,
    onToggle: (ShareTarget) -> Unit,
    onSend: () -> Unit,
) {
    val count = state.picked.size
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_title)) },
                navigationIcon = {
                    IconButton(
                        onBack,
                    ) { Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = onSend,
                enabled = count > 0 && !state.sending,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(
                    when {
                        state.sending -> stringResource(R.string.share_sending)
                        count == 0 -> stringResource(R.string.share_pick)
                        else -> pluralStringResource(R.plurals.share_send_to, count, count)
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ShareHeader(state, onSearch, onNote)
            ShareTargets(state, onToggle)
        }
    }
}

@Composable
private fun ShareHeader(
    state: ShareUiState,
    onSearch: (String) -> Unit,
    onNote: (String) -> Unit,
) {
    Column {
        Text(
            shareSummary(state),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The box keeps its own text: fed from the model's flow, it was redrawn with an older
        // value between fast keystrokes and the cursor jumped back (owner, Gate G7).
        var typed by rememberSaveable { mutableStateOf(state.query) }
        OutlinedTextField(
            typed,
            { text ->
                typed = text
                onSearch(text)
            },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text(stringResource(R.string.share_search)) },
            singleLine = true,
        )
        // A message to go with what is shared, sent first, with the link or files after it.
        var note by rememberSaveable { mutableStateOf(state.note) }
        OutlinedTextField(
            note,
            { text ->
                note = text
                onNote(text)
            },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text(stringResource(R.string.share_note)) },
            minLines = 1,
            maxLines = 4,
        )
    }
}

@Composable
private fun ShareTargets(
    state: ShareUiState,
    onToggle: (ShareTarget) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        items(state.chats, key = { it.key }) { target -> TargetRow(target, target.key in state.picked, onToggle) }
        if (state.people.isNotEmpty()) {
            item(key = "people") {
                Text(
                    stringResource(R.string.share_people),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(state.people, key = { it.key }) { target -> TargetRow(target, target.key in state.picked, onToggle) }
        }
    }
}

@Composable
private fun TargetRow(
    target: ShareTarget,
    picked: Boolean,
    onToggle: (ShareTarget) -> Unit,
) {
    ListItem(
        onClick = { onToggle(target) },
        leadingContent = { Avatar(target.title, size = 40.dp) },
        trailingContent = { Checkbox(picked, onCheckedChange = { onToggle(target) }) },
        supportingContent = { NetworkBadge(target.network) },
    ) { Text(target.title) }
}

@Composable
private fun shareSummary(state: ShareUiState): String {
    val files =
        if (state.fileCount >
            0
        ) {
            pluralStringResource(R.plurals.share_files, state.fileCount, state.fileCount)
        } else {
            null
        }
    val text =
        state.text.takeIf { it.isNotBlank() }?.let {
            "“" + it.take(TEXT_PREVIEW) +
                (if (it.length > TEXT_PREVIEW) "…" else "") +
                "”"
        }
    return listOfNotNull(files, text).joinToString(" · ")
}

private const val TEXT_PREVIEW = 80
