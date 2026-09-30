// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.app.appearance.Wallpaper
import org.pingme.core.model.CallMethod
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

/** The chat with its view model, one per chat. */
@Composable
fun ChatRoute(
    chatId: ChatId,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel =
        hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = chatId.value) { it.create(chatId.value) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    ChatScreen(
        state = state,
        actions =
            ChatScreenActions(
                header =
                    HeaderActions(
                        onBack = onBack,
                        onCall = { video ->
                            val outcome = placeCall(context, state, video)
                            if (outcome != CallOutcome.CALLING) {
                                val text =
                                    if (outcome ==
                                        CallOutcome.OPENED_APP
                                    ) {
                                        R.string.chat_call_opened_app
                                    } else {
                                        R.string.chat_calls_unavailable
                                    }
                                scope.launch { snackbar.showSnackbar(resources.getString(text)) }
                            }
                        },
                    ),
                onSend = viewModel::send,
                onReply = viewModel::reply,
                onRetry = viewModel::retry,
                onUnpin = viewModel::unpin,
                onLoadOlder = viewModel::loadOlder,
                onNeed = viewModel::need,
                onTyping = viewModel::typing,
            ),
        modifier = modifier,
        snackbar = snackbar,
    )
}

private fun placeCall(
    context: android.content.Context,
    state: ChatUiState,
    video: Boolean,
): CallOutcome {
    val network = state.account?.network ?: return CallOutcome.UNAVAILABLE
    val calls = state.capabilities?.calls ?: return CallOutcome.UNAVAILABLE
    val method = if (video) calls.video else calls.audio
    if (method == CallMethod.NONE) return CallOutcome.UNAVAILABLE
    return Calls(context).start(network, method, video, state.phone)
}

/**
 * The chat (UI_DESIGN.md 3.2): header, pinned banner, messages over the wallpaper drawn
 * newest at the bottom, a jump-to-newest button, the reply strip, and the composer.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatScreenActions,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val highlight = remember { mutableStateMapOf<String, Boolean>() }

    fun jumpTo(id: String) {
        val index = state.items.indexOfFirst { it.key == id }
        if (index < 0) return
        scope.launch {
            list.animateScrollToItem(index)
            highlight[id] = true
            delay(HIGHLIGHT_MS)
            highlight.remove(id)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                ChatHeader(state, actions.header)
                state.pinned.firstOrNull()?.let {
                    PinnedBanner(
                        it,
                        state,
                        { jumpTo(it.id.value) },
                    ) { actions.onUnpin(it) }
                }
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                state.replyTo?.let { ReplyStrip(it, state) { actions.onReply(null) } }
                Composer(actions.onSend, actions.onTyping)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Wallpaper(PingMeTheme.appearance.wallpaper, Modifier.fillMaxSize())
            MessageList(state, list, actions, highlight) { jumpTo(it) }
            ToNewest(list, state, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    list: LazyListState,
    actions: ChatScreenActions,
    highlight: Map<String, Boolean>,
    onJump: (String) -> Unit,
) {
    val chat = state.chat ?: return
    val network = state.account?.network ?: return
    val revealed = remember { mutableStateMapOf<String, Boolean>() }
    val timestamps = PingMeTheme.appearance.timestamps
    val context =
        RowContext(network, chat.kind, state.names, actions.onRetry, actions.onNeed) { m ->
            m.replyTo?.let {
                onJump(it.value)
            }
        }
    // The list is drawn from the bottom: index 0 is the newest message.
    LazyColumn(Modifier.fillMaxSize().testTag(CHAT_LIST), state = list, reverseLayout = true) {
        items(state.items, key = { it.key }) { item ->
            when (item) {
                is ChatItem.Bubble -> {
                    MessageRow(
                        item,
                        context,
                        Modifier
                            .animateItem()
                            .background(
                                if (highlight[item.key] ==
                                    true
                                ) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = PULSE)
                                } else {
                                    Color.Transparent
                                },
                            ),
                        showTime = showsTime(timestamps, item, revealed[item.key] == true),
                    )
                }

                is ChatItem.Day -> {
                    DayChip(dayLabel(item.date))
                }

                ChatItem.NewMessages -> {
                    NewMessagesLine()
                }
            }
        }
    }
    LoadOlderWhenNearTop(list, state, actions.onLoadOlder)
    StayAtBottom(list, state)
}

@Composable
private fun LoadOlderWhenNearTop(
    list: LazyListState,
    state: ChatUiState,
    onLoadOlder: () -> Unit,
) {
    val load by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(list, state.moreHistory) {
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index to list.layoutInfo.totalItemsCount
        }.collect { (last, total) -> if (state.moreHistory && last != null && last >= total - NEAR_TOP) load() }
    }
}

/** A new message while you are at the bottom keeps you at the bottom. */
@Composable
private fun StayAtBottom(
    list: LazyListState,
    state: ChatUiState,
) {
    val newest = state.items.firstOrNull()?.key
    LaunchedEffect(newest) {
        if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0)
    }
}

/** "Jump to newest", with how many arrived below while you were scrolled up (UI_DESIGN.md 3.2). */
@Composable
private fun ToNewest(
    list: LazyListState,
    state: ChatUiState,
    modifier: Modifier = Modifier,
) {
    val away by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > AWAY } }
    var seenNewest by remember { mutableStateOf<String?>(null) }
    val newest = state.items.firstOrNull()?.key
    LaunchedEffect(away) { if (!away) seenNewest = newest }
    val newer =
        if (away && seenNewest != null) {
            state.items.takeWhile { it.key != seenNewest }.count {
                (it as? ChatItem.Bubble)?.message?.isOutgoing ==
                    false
            }
        } else {
            0
        }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(away, modifier) {
        val label =
            if (newer >
                0
            ) {
                pluralStringResource(R.plurals.chat_new_below, newer, newer)
            } else {
                stringResource(R.string.chat_to_newest)
            }
        SmallFloatingActionButton(onClick = { scope.launch { list.animateScrollToItem(0) } }) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(UiR.drawable.ic_keyboard_arrow_down), label)
                if (newer > 0) Text("$newer", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        // A tonal chip, so the date reads on any wallpaper (UI_DESIGN.md 4.3).
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                label,
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun NewMessagesLine() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.chat_new_messages),
            Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}

const val CHAT_LIST = "chat-list"
const val COMPOSER = "composer"
private const val NEAR_TOP = 5
private const val AWAY = 2
private const val HIGHLIGHT_MS = 900L
private const val PULSE = 0.16f
