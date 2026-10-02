// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import org.pingme.app.chat.attach.composerHooks
import org.pingme.app.chat.search.ChatSearchResults
import org.pingme.app.chat.search.showing
import org.pingme.app.chat.voice.rememberVoicePlayer
import org.pingme.core.model.CallMethod
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.ui.theme.ChatLook
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.with
import org.pingme.core.ui.R as UiR

/** The chat with its view model, one per chat. */
@Composable
fun ChatRoute(
    chatId: ChatId,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onDetails: (() -> Unit)? = null,
    viewModel: ChatViewModel =
        hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = chatId.value) { it.create(chatId.value) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // On screen and resumed: no notifications for this chat; paused or gone: they come back.
    androidx.lifecycle.compose.LifecycleResumeEffect(chatId) {
        viewModel.visible(true)
        onPauseOrDispose { viewModel.visible(false) }
    }
    val forwardTargets by viewModel.forwardTargets.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val held by viewModel.heldBack.collectAsStateWithLifecycle()
    val player = rememberVoicePlayer()
    val searching by viewModel.search.state.collectAsStateWithLifecycle()
    val overrides by viewModel.overrides.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val jump by viewModel.jumps.request.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val notice: (Int) -> Unit = { scope.launch { snackbar.showSnackbar(resources.getString(it)) } }
    // This chat's own look from Chat details, over the app's (UI_DESIGN.md 3.4).
    val app = PingMeTheme.appearance
    val look = remember(overrides.lookJson) { ChatLook.fromJson(overrides.lookJson) }
    val network = state.account?.network
    PingMeTheme(if (network == null || look.isEmpty) app else app.with(look, network)) {
        ChatScreen(
            state = state,
            actions =
                ChatScreenActions(
                    header = headerActions(viewModel, state, onBack, context, notice).copyWithDetails(onDetails),
                    onSend = { viewModel.send(it) },
                    onReply = viewModel::reply,
                    onRetry = viewModel::retry,
                    onUnpin = viewModel::unpin,
                    onLoadOlder = viewModel::loadOlder,
                    onNeed = viewModel::need,
                    onTyping = viewModel::typing,
                    menu = viewModel.menu,
                    onRememberEmoji = viewModel::rememberEmoji,
                    incoming = viewModel.incomingReactions,
                    forwardTargets = forwardTargets,
                    uploads = uploads,
                    player = player,
                    settings = appSettings,
                    transcripts = viewModel.transcripts.takeIf { appSettings.media.transcribeVoice },
                    search = searchHooks(viewModel, searching, jump),
                    composer = composerHooks(viewModel, state, notice),
                    cleanLink = { if (appSettings.privacy.cleanLinksReceived) viewModel.links.clean(it) else it },
                ),
            modifier = modifier,
            snackbar = snackbar,
        )
    }
    org.pingme.app.chat.attach
        .HeldBackDialog(held, viewModel::answerHeldBack, viewModel::shrinkHeldBack)
}

/**
 * The chat (UI_DESIGN.md 3.2): header, pinned banner, messages over the wallpaper drawn
 * newest at the bottom, a jump-to-newest button, the reply strip, and the composer.
 * Selecting swaps the header and composer for the selection bars (3.3).
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
    val ui = remember { ChatUi() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Notices(actions.menu, snackbar)

    fun jumpTo(id: String) {
        val index = state.items.indexOfFirst { it.isMessage(id) }
        if (index < 0) return
        scope.launch {
            list.animateScrollToItem(index)
            highlight[id] = true
            delay(HIGHLIGHT_MS)
            highlight.remove(id)
        }
    }
    // A message search asked for: scroll there once the list has loaded back to it.
    val jump = actions.search.jump
    LaunchedEffect(jump, state.items) {
        if (jump != null && state.items.any { it.isMessage(jump.value) }) {
            jumpTo(jump.value)
            actions.search.onJumped()
        }
    }
    SecureWindow(state.chat?.isObscured == true)
    // One Box, so the overlays and the reaction bursts lie over the chat whatever holds it:
    // a list-detail pane stacks its children, which left the burst layer no height at all.
    Box(modifier) {
        Scaffold(
            topBar = { TopBars(state, actions) { jumpTo(it) } },
            bottomBar = { BottomBars(state, actions, ui, context) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            // While a message is held, everything else blurs behind it (UI_DESIGN.md 3.3).
            Box(Modifier.fillMaxSize().padding(padding).blur(if (ui.holding != null) HELD_BLUR else 0.dp)) {
                Wallpaper(PingMeTheme.appearance.wallpaper, Modifier.fillMaxSize())
                MessageList(state, list, actions, highlight, ui) { jumpTo(it) }
                ToNewest(list, state, Modifier.align(Alignment.BottomEnd).padding(16.dp))
                if (actions.search.state.showing) {
                    ChatSearchResults(actions.search.state, state.names, actions.search.onOpen)
                }
            }
        }
        ChatOverlays(ui, state, actions, context, haptic) { held ->
            val item = state.items.filterIsInstance<ChatItem.Bubble>().firstOrNull { it.message.id == held.id }
            if (item != null) MessageRow(item, rowContext(state, actions) {}, showTime = true)
        }
    }
}

internal fun rowContext(
    state: ChatUiState,
    actions: ChatScreenActions,
    onJump: (String) -> Unit,
): RowContext =
    RowContext(
        state.account!!.network,
        state.chat!!.kind,
        state.names,
        actions.onRetry,
        actions.onNeed,
        { m -> m.replyTo?.let { onJump(it.value) } },
        actions.uploads,
        actions.player,
        actions.settings.media.gifsAutoplay,
        actions.transcripts,
        actions.cleanLink,
    )

@Composable
private fun MessageList(
    state: ChatUiState,
    list: LazyListState,
    actions: ChatScreenActions,
    highlight: Map<String, Boolean>,
    ui: ChatUi,
    onJump: (String) -> Unit,
) {
    if (state.chat == null || state.account == null) return
    val haptic = LocalHapticFeedback.current
    val haptics = PingMeTheme.appearance.haptics
    val timestamps = PingMeTheme.appearance.timestamps
    val context = rowContext(state, actions, onJump)
    val obscured = state.chat.isObscured
    val cover = MaterialTheme.colorScheme.surfaceContainerHighest
    val hiddenLabel = stringResource(R.string.obscured_hidden)
    SideEffect { ui.shown = state.items.mapTo(HashSet()) { it.messageId ?: it.key } }
    // The list is drawn from the bottom: index 0 is the newest message.
    LazyColumn(Modifier.fillMaxSize().testTag(CHAT_LIST), state = list, reverseLayout = true) {
        items(state.items, key = { it.key }) { item ->
            val placement = Modifier.itemMotion(this, PingMeTheme.motion)
            when (item) {
                is ChatItem.Bubble -> {
                    // The screen's own maps go by message id; the list's key may be the pending id.
                    val key = item.message.id.value
                    val tint =
                        when {
                            item.message.id in state.selection -> {
                                MaterialTheme.colorScheme.secondaryContainer.copy(
                                    alpha = SELECTED,
                                )
                            }

                            highlight[key] == true -> {
                                MaterialTheme.colorScheme.primary.copy(alpha = PULSE)
                            }

                            else -> {
                                Color.Transparent
                            }
                        }
                    Entrance(item.message, ui, placement.background(tint)) {
                        MessageTouch(
                            gestures = gesturesFor(item.message, state, actions, ui, haptic, haptics),
                            wobble = ui.wobble[key] ?: 0,
                        ) {
                            HideAgain(key, ui.unblurred)
                            MessageRow(
                                item,
                                context,
                                showTime = showsTime(timestamps, item, ui.revealed[key] == true),
                                bubbleModifier =
                                    Modifier
                                        .onGloballyPositioned { ui.bounds[key] = it.boundsInRoot() }
                                        .obscured(obscured && ui.unblurred[key] != true, cover, hiddenLabel),
                            )
                        }
                    }
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

/** Tap reveals the time or toggles selection; double tap reacts; hold opens the menu or selects. */
private fun gesturesFor(
    message: Message,
    state: ChatUiState,
    actions: ChatScreenActions,
    ui: ChatUi,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    haptics: org.pingme.core.ui.theme.Haptics,
): BubbleGestures {
    val selecting = state.selection.isNotEmpty()
    val key = message.id.value

    // A bubble on its way out of the list takes no touches (see ChatUi.shown).
    fun live(action: () -> Unit): () -> Unit = { if (key in ui.shown) action() }
    return BubbleGestures(
        onTap =
            live {
                when {
                    selecting -> actions.menu?.toggle(message)

                    // In an obscured chat a tap shows the message for a few seconds (UI_DESIGN.md 10.10).
                    state.chat?.isObscured == true && ui.unblurred[key] != true -> ui.unblurred[key] = true

                    else -> ui.revealed[key] = ui.revealed[key] != true
                }
            },
        onDoubleTap =
            live {
                val prefs = state.reactions
                val emoji = actions.menu?.doubleTapEmoji(prefs.doubleTap, prefs.quick, state.capabilities?.reactions)
                if (emoji != null) ui.react(message, emoji, null, state, actions, haptic, haptics)
            },
        onHold = live { if (selecting) actions.menu?.toggle(message) else ui.holding = message },
        onSwipeReply = live { actions.onReply(message) },
    )
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
        }.collect { (last, total) ->
            // An empty chat has no top to scroll to, so it asks for its history straight away.
            val nearTop = total == 0 || (last != null && last >= total - NEAR_TOP)
            if (state.moreHistory && nearTop) load()
        }
    }
}

/**
 * The chat opens at its newest message. A message that arrives while you are near the
 * bottom takes you to it; your own send takes you there wherever you were, and so does
 * the keyboard opening while you are near the bottom (owner, Gate G2: sent and received
 * messages hid below the keyboard).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StayAtBottom(
    list: LazyListState,
    state: ChatUiState,
) {
    val newestItem = state.items.firstOrNull()
    val newest = newestItem?.key
    val mine = (newestItem as? ChatItem.Bubble)?.message?.isOutgoing == true
    LaunchedEffect(newest) {
        if (newest == null) return@LaunchedEffect
        if (mine || list.firstVisibleItemIndex <= NEAR_BOTTOM) list.scrollToItem(0)
    }
    val keyboardUp = WindowInsets.isImeVisible
    LaunchedEffect(keyboardUp) {
        if (keyboardUp && list.firstVisibleItemIndex <= NEAR_BOTTOM) list.scrollToItem(0)
    }
}

private const val NEAR_BOTTOM = 3

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

/** The message this line shows, if it is a bubble. Its list key may differ (a sent message keeps its pending key). */
private val ChatItem.messageId: String? get() = (this as? ChatItem.Bubble)?.message?.id?.value

private fun ChatItem.isMessage(id: String) = messageId == id || key == id

const val CHAT_LIST = "chat-list"
const val COMPOSER = "composer"
private const val NEAR_TOP = 5
private const val AWAY = 2
private const val HIGHLIGHT_MS = 900L
private const val PULSE = 0.16f
private const val SELECTED = 0.5f
private val HELD_BLUR = 8.dp
