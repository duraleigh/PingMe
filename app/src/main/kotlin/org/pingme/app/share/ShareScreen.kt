// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.core.model.ChatId
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
    ShareScreen(
        state,
        snackbar,
        ShareScreenActions(
            onBack,
            viewModel::search,
            viewModel::note,
            viewModel::toggle,
            viewModel::send,
            viewModel::sendToGroup,
        ),
    )
}

/** What the share screen's controls do. */
private class ShareScreenActions(
    val onBack: () -> Unit,
    val onSearch: (String) -> Unit,
    val onNote: (String) -> Unit,
    val onToggle: (ShareTarget) -> Unit,
    val onSend: () -> Unit,
    val onSendToGroup: () -> Unit,
)

/**
 * Laid out as Google Messages' Share (owner, 2026-11-04, from his phone): search behind the bar's
 * magnifier, the recent conversations in one card, everyone A to Z under them, and at the foot
 * the message, "Send to a group", and "Send".
 */
@Composable
private fun ShareScreen(
    state: ShareUiState,
    snackbar: SnackbarHostState,
    actions: ShareScreenActions,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            ShareBar(searching, state.query, actions, onSearching = { searching = it })
        },
        bottomBar = { ShareFoot(state, actions) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ShareTargets(state, searching, actions.onToggle, Modifier.fillMaxSize().padding(padding))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ShareBar(
    searching: Boolean,
    query: String,
    actions: ShareScreenActions,
    onSearching: (Boolean) -> Unit,
) {
    // The box keeps its own text: fed from the model's flow, it was redrawn with an older
    // value between fast keystrokes and the cursor jumped back (owner, Gate G7).
    var typed by rememberSaveable { mutableStateOf(query) }
    val close = {
        typed = ""
        actions.onSearch("")
        onSearching(false)
    }
    TopAppBar(
        title = {
            if (searching) {
                TextField(
                    typed,
                    { text ->
                        typed = text
                        actions.onSearch(text)
                    },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.share_search)) },
                    singleLine = true,
                    colors = clearField(),
                )
            } else {
                Text(stringResource(R.string.share_title))
            }
        },
        navigationIcon = {
            IconButton(if (searching) close else actions.onBack) {
                Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back))
            }
        },
        actions = {
            if (!searching) {
                IconButton({ onSearching(true) }) {
                    Icon(painterResource(UiR.drawable.ic_search), stringResource(R.string.share_search))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun clearField() =
    TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )

@Composable
private fun ShareTargets(
    state: ShareUiState,
    searching: Boolean,
    onToggle: (ShareTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Recent conversations, a few and then the rest on asking, as in Google Messages (owner, 2026-11-01).
    var more by rememberSaveable { mutableStateOf(false) }
    val narrowed = state.query.isNotBlank()
    val shown =
        if (more || narrowed) {
            state.chats
        } else {
            state.chats.take(RECENT_SHOWN) + state.chats.drop(RECENT_SHOWN).filter { it.key in state.picked }
        }
    LazyColumn(modifier.padding(top = 8.dp)) {
        if (!searching) item(key = "recent") { Heading(stringResource(R.string.share_recent)) }
        card(shown, state.picked, onToggle)
        if (shown.size < state.chats.size) {
            item(key = "more") {
                TextButton({ more = true }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.share_more_recent), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        // Everyone, a letter over each run, as in Google Messages.
        state.people.groupBy(::letterOf).forEach { (letter, run) ->
            item(key = "letter-$letter") { Heading(letter) }
            card(run, state.picked, onToggle)
        }
    }
}

private fun LazyListScope.card(
    targets: List<ShareTarget>,
    picked: Set<String>,
    onToggle: (ShareTarget) -> Unit,
) = itemsIndexed(targets, key = { _, t -> t.key }) { i, target ->
    ShareRow(target, target.key in picked, first = i == 0, last = i == targets.lastIndex, onToggle)
}

@Composable
private fun Heading(text: String) =
    Text(
        text,
        Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
    )

private fun letterOf(target: ShareTarget): String =
    target.title
        .firstOrNull()
        ?.takeIf(Char::isLetter)
        ?.uppercase() ?: "#"

/** The foot: the message, then "Send to a group" beside "Send". */
@Composable
private fun ShareFoot(
    state: ShareUiState,
    actions: ShareScreenActions,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.fileCount > 0) {
                Text(
                    pluralStringResource(R.plurals.share_files, state.fileCount, state.fileCount),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Shared words sit here to be read and changed; with files, a message to go with them.
            var note by rememberSaveable { mutableStateOf(state.note) }
            TextField(
                note,
                { text ->
                    note = text
                    actions.onNote(text)
                },
                Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.share_note)) },
                maxLines = NOTE_LINES,
                shape = RoundedCornerShape(28.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
            )
            ShareButtons(state, actions)
        }
    }
}

@Composable
private fun ShareButtons(
    state: ShareUiState,
    actions: ShareScreenActions,
) {
    val ready = state.picked.isNotEmpty() && !state.sending
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            actions.onSendToGroup,
            Modifier.weight(1f).heightIn(min = 56.dp),
            enabled = state.canGroup && !state.sending,
        ) { Text(stringResource(R.string.share_send_group), textAlign = TextAlign.Center) }
        Button(
            actions.onSend,
            Modifier.weight(1f).heightIn(min = 56.dp),
            enabled = ready,
        ) {
            Text(stringResource(if (state.sending) R.string.share_sending else R.string.share_send))
        }
    }
}

private const val RECENT_SHOWN = 5
private const val NOTE_LINES = 4
