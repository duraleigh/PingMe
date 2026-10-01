// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.app.chat.itemMotion
import org.pingme.core.model.ChatId
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

@Composable
fun ChatListRoute(
    onBack: () -> Unit,
    onOpenChat: (ChatId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ShowMessages(viewModel.messages, snackbar)
    ChatListScreen(
        route = viewModel.route,
        state = state,
        onBack = onBack,
        onOpenChat = onOpenChat,
        onSwipe = viewModel::swipe,
        onAction = viewModel::perform,
        onRespond = viewModel::respond,
        modifier = modifier,
        snackbar = snackbar,
    )
}

/** Shows row-action snackbars and runs their Undo or their "gone" step. */
@Composable
fun ShowMessages(
    messages: Flow<InboxMessage>,
    snackbar: SnackbarHostState,
) {
    val resources = LocalResources.current
    LaunchedEffect(messages) {
        messages.collect { message ->
            launch {
                val text =
                    message.chatTitle?.let { resources.getString(message.text, it) }
                        ?: resources.getString(message.text)
                val result =
                    snackbar.showSnackbar(
                        text,
                        actionLabel = message.undo?.let { resources.getString(R.string.undo) },
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed) message.undo?.invoke() else message.onGone()
            }
        }
    }
}

/** One of the avatar menu's lists, with the same rows, swipes and sheet as the inbox. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    route: ChatListRoute,
    state: ChatListUiState,
    onBack: () -> Unit,
    onOpenChat: (ChatId) -> Unit,
    onSwipe: (ChatRow, org.pingme.core.ui.theme.SwipeAction) -> Unit,
    onAction: (ChatRow, ChatAction) -> Unit,
    onRespond: (ChatRow, RequestResponse) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var holding by remember { mutableStateOf<ChatRow?>(null) }
    val (title, empty) = labels(route)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) { Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(state.rows, key = { it.id.value }) { row ->
                Column(Modifier.itemMotion(this, PingMeTheme.motion)) {
                    InboxRow(row, state.now, { onOpenChat(row.id) }, { holding = row }, { onSwipe(row, it) })
                    if (route.kind == ListKind.REQUESTS) RequestButtons { onRespond(row, it) }
                }
            }
            if (!state.loading && state.rows.isEmpty()) {
                item {
                    Text(
                        empty,
                        Modifier.padding(40.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    holding?.let { row -> ChatActionSheet(row, { onAction(row, it) }, { holding = null }) }
}

/** Accept, Decline, Block under each request (UI_DESIGN.md 6.4). */
@Composable
private fun RequestButtons(onRespond: (RequestResponse) -> Unit) {
    Row(
        Modifier.padding(start = 86.dp, end = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { onRespond(RequestResponse.ACCEPT) }) { Text(stringResource(R.string.request_accept)) }
        OutlinedButton(
            onClick = { onRespond(RequestResponse.DECLINE) },
        ) { Text(stringResource(R.string.request_decline)) }
        TextButton(onClick = { onRespond(RequestResponse.BLOCK) }) {
            Text(stringResource(R.string.request_block), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun labels(route: ChatListRoute): Pair<String, String> =
    when (route.kind) {
        ListKind.ARCHIVED -> {
            stringResource(R.string.menu_archived) to stringResource(R.string.list_empty_archived)
        }

        ListKind.LOW_PRIORITY -> {
            stringResource(R.string.menu_low_priority) to
                stringResource(R.string.list_empty_low_priority)
        }

        ListKind.REQUESTS -> {
            stringResource(R.string.menu_requests) to stringResource(R.string.list_empty_requests)
        }

        ListKind.GENERAL -> {
            stringResource(R.string.menu_general) to stringResource(R.string.list_empty_general)
        }

        ListKind.SPACE -> {
            route.title.orEmpty() to stringResource(R.string.list_empty_space)
        }
    }
