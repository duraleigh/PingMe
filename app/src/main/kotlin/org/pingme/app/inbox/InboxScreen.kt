// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import org.pingme.core.ui.R as UiR

/** Where the inbox leads. */
class InboxNavigation(
    val onOpenChat: (ChatId) -> Unit,
    val onSearch: () -> Unit,
    val onNewChat: () -> Unit,
    val onNewGroup: () -> Unit,
    val onFix: (Account) -> Unit,
    val menu: MenuActions,
    /** The merge suggestions screen (owner, Phase 7). */
    val onMergeSuggestions: () -> Unit = {},
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
                onBulk = viewModel::bulk,
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
    /** One action on every selected row (owner, Gate G3). */
    val onBulk: (List<ChatRow>, BulkAction) -> Unit = { _, _ -> },
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
    // Hold an avatar to start selecting rows; the bar above acts on all of them (owner, Gate G3).
    val selection = remember { RowSelection() }
    val selectedRows = (state.pinned + state.rows).filter { it.id in selection.ids }
    BackHandler(enabled = selection.ids.isNotEmpty()) { selection.clear() }
    val flips = remember { mutableStateMapOf<ChatId, String>() }
    LaunchedEffect(reactions) { reactions.collect { flips[it.chatId] = it.emoji } }

    val wide = isWide()
    val menu = navigation.menu.withEditBar { editingBar = true }
    Row(modifier.fillMaxSize()) {
        if (wide) InboxRail(state.bar, state.selected, state.accounts, callbacks.bar)
        Scaffold(
            topBar = {
                InboxTop(state, callbacks, navigation, menu, selectedRows, selection::clear) { action ->
                    if (action == BulkAction.DELETE) {
                        selection.confirmingDelete = true
                    } else {
                        callbacks.onBulk(selectedRows, action)
                        selection.clear()
                    }
                }
            },
            bottomBar = { if (!wide) InboxBottomBar(state.bar, state.selected, state.accounts, callbacks.bar) },
            floatingActionButton = { NewMenu(fabOpen, { fabOpen = it }, navigation.onNewChat, navigation.onNewGroup) },
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
                selected = selection.ids,
                onSelect = selection::toggle,
                onSuggestions = navigation.onMergeSuggestions,
            )
        }
    }
    holding?.let { row -> ChatActionSheet(row, { callbacks.onAction(row, it) }, { holding = null }) }
    if (selection.confirmingDelete) {
        ConfirmBulkDelete(
            count = selectedRows.size,
            onConfirm = {
                callbacks.onBulk(selectedRows, BulkAction.DELETE)
                selection.clear()
            },
            onDismiss = { selection.confirmingDelete = false },
        )
    }
    if (editingBar) EditBarSheet(state, callbacks.onSaveBar) { editingBar = false }
}

@Composable
private fun EditBarSheet(
    state: InboxUiState,
    onSave: (List<InboxBarItem?>) -> Unit,
    onDismiss: () -> Unit,
) = EditBarSheet(
    current = state.bar.map { it.item },
    accounts = state.accounts,
    spaces = state.menu.spaces.map { it.first },
    onSave = onSave,
    onDismiss = onDismiss,
)

/** Which rows are selected, and whether deleting them is being confirmed (owner, Gate G3). */
@androidx.compose.runtime.Stable
private class RowSelection {
    var ids by mutableStateOf(setOf<ChatId>())
    var confirmingDelete by mutableStateOf(false)

    fun toggle(row: ChatRow) {
        ids = if (row.id in ids) ids - row.id else ids + row.id
    }

    fun clear() {
        ids = emptySet()
        confirmingDelete = false
    }
}

/** Tablets and unfolded foldables: a rail and room for the chat beside the list (UI_DESIGN.md 3.7). */
@Composable
private fun isWide() =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

private fun MenuActions.withEditBar(onEdit: () -> Unit) =
    MenuActions(onAppearance, onList, onEdit, onSettings, onNotifications, onAccounts, onMergeSuggestions)

/** Callbacks shared by the bottom bar and the rail. */
class BarActions(
    val onSelect: (InboxBarItem?) -> Unit,
    val onNarrow: (NetworkId, Narrowing?) -> Unit,
)

/** The inbox's top bar, or the selection bar while rows are selected. */
@Composable
private fun InboxTop(
    state: InboxUiState,
    callbacks: InboxCallbacks,
    navigation: InboxNavigation,
    menu: MenuActions,
    selectedRows: List<ChatRow>,
    onClearSelection: () -> Unit,
    onBulk: (BulkAction) -> Unit,
) {
    if (selectedRows.isNotEmpty()) {
        SelectionBar(count = selectedRows.size, onClose = onClearSelection, onAct = onBulk)
    } else {
        // Its own surface with rounded bottom corners, so the list scrolls cleanly under it (3.1).
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = TOP_BAR_SHAPE) {
            Column(Modifier.padding(bottom = 8.dp)) {
                InboxTopBar(state.accounts, state.menu, callbacks.onStatus, navigation.onSearch, menu)
                HealthChip(state.problems, navigation.onFix)
            }
        }
    }
}

/** Delete has no Undo for several chats at once, so it asks first. */
@Composable
private fun ConfirmBulkDelete(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.inbox_bulk_delete_title, count, count)) },
        text = { Text(stringResource(R.string.inbox_bulk_delete_note)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onConfirm) { Text(stringResource(R.string.action_delete_message)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * Stands in for the top bar while rows are selected: how many, a way out, and what to do
 * with them all: mark read, mark unread, mute, archive, low priority, delete (owner, Gate G3).
 */
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onAct: (BulkAction) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = TOP_BAR_SHAPE) {
        Column(Modifier.statusBarsPadding().padding(bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) {
                    Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.inbox_selection_close))
                }
                Text(
                    pluralStringResource(R.plurals.selected, count, count),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BulkButton(BulkAction.READ, UiR.drawable.ic_mark_chat_read, R.string.action_read, onAct)
                BulkButton(BulkAction.UNREAD, UiR.drawable.ic_mark_chat_unread, R.string.action_unread, onAct)
                BulkButton(BulkAction.MUTE, UiR.drawable.ic_notifications_off, R.string.action_mute, onAct)
                BulkButton(BulkAction.ARCHIVE, UiR.drawable.ic_archive, R.string.action_archive, onAct)
                BulkButton(BulkAction.LOW_PRIORITY, UiR.drawable.ic_low_priority, R.string.action_low_priority, onAct)
                BulkButton(BulkAction.MERGE, UiR.drawable.ic_call_merge, R.string.action_merge, onAct)
                BulkButton(BulkAction.DELETE, UiR.drawable.ic_delete, R.string.action_delete, onAct)
            }
        }
    }
}

@Composable
private fun BulkButton(
    action: BulkAction,
    @androidx.annotation.DrawableRes icon: Int,
    @androidx.annotation.StringRes label: Int,
    onAct: (BulkAction) -> Unit,
) {
    IconButton({ onAct(action) }) { Icon(painterResource(icon), stringResource(label)) }
}

@Composable
private fun ChatListBody(
    state: InboxUiState,
    flips: Map<ChatId, String>,
    onOpen: (ChatRow) -> Unit,
    onHold: (ChatRow) -> Unit,
    onSwipe: (ChatRow, SwipeAction) -> Unit,
    onFlipEnd: (ChatId) -> Unit,
    contentPadding: PaddingValues,
    selected: Set<ChatId> = emptySet(),
    onSelect: (ChatRow) -> Unit = {},
    onSuggestions: () -> Unit = {},
) {
    val style = PingMeTheme.appearance.pinnedStyle
    // "Top of list" shows pinned chats as ordinary rows above the rest (UI_DESIGN.md 4.3).
    val rows = if (style == PinnedStyle.TOP_OF_LIST) state.pinned + state.rows else state.rows
    // Coming to the inbox always lands at the top, never where the list was last left (owner, Gate G7).
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(Unit) { listState.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().testTag(INBOX_LIST), state = listState, contentPadding = contentPadding) {
        // People who look the same on more than one network: PingMe proposes, the user decides (10.15).
        if (state.menu.suggestions > 0 && state.selected == null) {
            item(key = "suggestions") { SuggestionsCard(state.menu.suggestions, onSuggestions) }
        }
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
                selecting = selected.isNotEmpty(),
                selected = row.id in selected,
                onSelect = { onSelect(row) },
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
