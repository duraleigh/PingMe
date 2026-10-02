// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.app.chat.itemMotion
import org.pingme.core.model.Account
import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId
import org.pingme.core.service.IncomingReaction
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.PinnedStyle
import org.pingme.core.ui.theme.SwipeAction

/** Where the inbox leads. */
class InboxNavigation(
    val onOpenChat: (ChatId) -> Unit,
    val onSearch: () -> Unit,
    val onNewChat: () -> Unit,
    val onNewGroup: () -> Unit,
    val onFix: (Account) -> Unit,
    val menu: MenuActions,
)

/** The inbox with its view model. */
@Composable
fun InboxRoute(
    navigation: InboxNavigation,
    modifier: Modifier = Modifier,
    viewModel: InboxViewModel =
        androidx.hilt.lifecycle.viewmodel.compose
            .hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ShowMessages(viewModel.messages, snackbar)
    val remind by viewModel.gmessagesReminder.collectAsStateWithLifecycle()
    if (remind) GmessagesReminder(viewModel::dismissGmessagesReminder)
    InboxScreen(
        state = state,
        callbacks =
            InboxCallbacks(
                onSwipe = viewModel::swipe,
                onAction = viewModel::perform,
                onStatus = {
                    if (state.accounts.any { it.network == org.pingme.core.model.NetworkId.DEMO }) {
                        viewModel.cycleDemoState()
                    } else {
                        state.problems.firstOrNull()?.let(navigation.onFix)
                    }
                },
                bar = BarActions(viewModel::select, viewModel::narrow),
                onSaveBar = viewModel::setBarItems,
            ),
        navigation = navigation,
        reactions = viewModel.reactions,
        snackbar = snackbar,
        modifier = modifier,
    )
}

/**
 * Once, after Google Messages is connected: a reminder to turn off its own notifications, so
 * texts do not arrive twice (UI_DESIGN.md 6.3). A reminder only; the owner opens Google's
 * settings themselves (owner decision, 2026-10-02).
 */
@Composable
private fun GmessagesReminder(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(stringResource(R.string.notify_gm_title)) },
        text = { androidx.compose.material3.Text(stringResource(R.string.notify_gm_note)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onDismiss) {
                androidx.compose.material3.Text(stringResource(R.string.notify_gm_got_it))
            }
        },
    )
}

/** What the inbox does with a row or the bar. */
class InboxCallbacks(
    val onSwipe: (ChatRow, SwipeAction) -> Unit,
    val onAction: (ChatRow, ChatAction) -> Unit,
    val onStatus: () -> Unit,
    val bar: BarActions,
    /** The bar's buttons from the editor, null standing for All. */
    val onSaveBar: (List<InboxBarItem?>) -> Unit,
)

/**
 * The inbox (UI_DESIGN.md 3.1): compact top bar, health chip, pinned chats, the list, the
 * + menu, and the bottom bar; a navigation rail instead of the bar on wide screens (3.7).
 */
@Composable
fun InboxScreen(
    state: InboxUiState,
    callbacks: InboxCallbacks,
    navigation: InboxNavigation,
    modifier: Modifier = Modifier,
    reactions: Flow<IncomingReaction> = emptyFlow(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var holding by remember { mutableStateOf<ChatRow?>(null) }
    var editingBar by remember { mutableStateOf(false) }
    var fabOpen by remember { mutableStateOf(false) }
    val flips = remember { mutableStateMapOf<ChatId, String>() }
    LaunchedEffect(reactions) { reactions.collect { flips[it.chatId] = it.emoji } }

    val wide = isWide()
    val menu = navigation.menu.withEditBar { editingBar = true }
    Row(modifier.fillMaxSize()) {
        if (wide) InboxRail(state.bar, state.selected, state.accounts, callbacks.bar)
        Scaffold(
            topBar = {
                // Its own surface with rounded bottom corners, so the list scrolls cleanly under it (3.1).
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = TOP_BAR_SHAPE) {
                    Column(Modifier.padding(bottom = 8.dp)) {
                        InboxTopBar(state.accounts, state.menu, callbacks.onStatus, navigation.onSearch, menu)
                        HealthChip(state.problems, navigation.onFix)
                    }
                }
            },
            bottomBar = { if (!wide) InboxBottomBar(state.bar, state.selected, state.accounts, callbacks.bar) },
            floatingActionButton = {
                NewMenu(
                    fabOpen,
                    { fabOpen = it },
                    navigation.onNewChat,
                    navigation.onNewGroup,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            ChatListBody(
                state = state,
                flips = flips,
                onOpen = { navigation.onOpenChat(it.id) },
                onHold = { holding = it },
                onSwipe = callbacks.onSwipe,
                onFlipEnd = { flips.remove(it) },
                contentPadding = padding,
            )
        }
    }
    holding?.let { row ->
        ChatActionSheet(row, { callbacks.onAction(row, it) }, { holding = null })
    }
    if (editingBar) {
        EditBarSheet(
            current = state.bar.map { it.item },
            accounts = state.accounts,
            spaces = state.menu.spaces.map { it.first },
            onSave = callbacks.onSaveBar,
            onDismiss = { editingBar = false },
        )
    }
}

/** Tablets and unfolded foldables: a rail and room for the chat beside the list (UI_DESIGN.md 3.7). */
@Composable
private fun isWide() =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

private fun MenuActions.withEditBar(onEdit: () -> Unit) =
    MenuActions(onAppearance, onList, onEdit, onSettings, onNotifications, onAccounts)

/** Callbacks shared by the bottom bar and the rail. */
class BarActions(
    val onSelect: (InboxBarItem?) -> Unit,
    val onNarrow: (NetworkId, Narrowing?) -> Unit,
)

@Composable
private fun ChatListBody(
    state: InboxUiState,
    flips: Map<ChatId, String>,
    onOpen: (ChatRow) -> Unit,
    onHold: (ChatRow) -> Unit,
    onSwipe: (ChatRow, SwipeAction) -> Unit,
    onFlipEnd: (ChatId) -> Unit,
    contentPadding: PaddingValues,
) {
    val style = PingMeTheme.appearance.pinnedStyle
    // "Top of list" shows pinned chats as ordinary rows above the rest (UI_DESIGN.md 4.3).
    val rows = if (style == PinnedStyle.TOP_OF_LIST) state.pinned + state.rows else state.rows
    LazyColumn(Modifier.fillMaxSize().testTag(INBOX_LIST), contentPadding = contentPadding) {
        if (state.pinned.isNotEmpty() && style != PinnedStyle.TOP_OF_LIST) {
            item(key = "pinned") {
                Column {
                    if (style == PinnedStyle.GRID) {
                        PinnedGrid(state.pinned, onOpen, onHold)
                    } else {
                        PinnedRow(state.pinned, onOpen, onHold)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
        }
        items(rows, key = { it.id.value }) { row ->
            InboxRow(
                row = row,
                now = state.now,
                onOpen = { onOpen(row) },
                onHold = { onHold(row) },
                onSwipe = { onSwipe(row, it) },
                modifier = Modifier.itemMotion(this, PingMeTheme.motion),
                flipEmoji = flips[row.id],
                onFlipEnd = { onFlipEnd(row.id) },
            )
        }
        if (!state.loading && rows.isEmpty() && state.pinned.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxSize().padding(40.dp)) {
                    Text(
                        stringResource(
                            if (state.selected ==
                                null
                            ) {
                                R.string.inbox_empty
                            } else {
                                R.string.inbox_empty_filter
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

const val INBOX_LIST = "inbox-list"

private val TOP_BAR_SHAPE = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
