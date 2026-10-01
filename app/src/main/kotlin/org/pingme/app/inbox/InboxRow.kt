// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import org.pingme.app.R
import org.pingme.app.chat.StatusMark
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Transport
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.theme.Haptics
import org.pingme.core.ui.theme.InboxDensity
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.SwipeAction
import kotlin.math.roundToInt
import kotlin.time.Instant
import org.pingme.core.ui.R as UiR

/** Where a swiped row is: at rest, or pulled right (start) or left (end) far enough to act. */
private enum class SwipeSide { REST, START, END }

/**
 * One inbox row (UI_DESIGN.md 3.1): avatar with typing dots, name, mute icon, network
 * badge, folder tag, time, preview, unread badge. Swipes act and spring back; press and
 * hold opens the action sheet; an incoming reaction flips the row for a moment (10.8).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxRow(
    row: ChatRow,
    now: Instant,
    onOpen: () -> Unit,
    onHold: () -> Unit,
    onSwipe: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    flipEmoji: String? = null,
    onFlipEnd: () -> Unit = {},
) {
    val appearance = PingMeTheme.appearance
    val right = appearance.swipeRight
    val left = appearance.swipeLeft
    val state = rememberSwipeState(right, left)
    val act by rememberUpdatedState(onSwipe)
    SwipeFeedback(state, appearance.haptics)
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { side ->
            if (side != SwipeSide.REST) {
                act(if (side == SwipeSide.START) right else left)
                state.animateTo(SwipeSide.REST)
            }
        }
    }
    val hold = stringResource(R.string.inbox_chat_actions)
    val rightLabel = stringResource(swipeLook(right, row).label)
    val leftLabel = stringResource(swipeLook(left, row).label)
    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .swipeActions(right to rightLabel, left to leftLabel, onSwipe = onSwipe),
    ) {
        val offset = state.offset.takeIf { !it.isNaN() } ?: 0f
        if (offset > 0f) SwipeReveal(right, row, alignStart = true)
        if (offset < 0f) SwipeReveal(left, row, alignStart = false)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier =
                Modifier
                    .offset { IntOffset(offset.roundToInt(), 0) }
                    .anchoredDraggable(
                        state,
                        Orientation.Horizontal,
                        flingBehavior =
                            AnchoredDraggableDefaults.flingBehavior(state, positionalThreshold = {
                                it *
                                    THRESHOLD
                            }),
                    ).combinedClickable(
                        onClickLabel = null,
                        onLongClickLabel = hold,
                        onLongClick = onHold,
                        onClick = onOpen,
                    ),
        ) {
            FlipCard(flipEmoji, onFlipEnd) { RowContent(row, now) }
        }
    }
}

@Composable
private fun rememberSwipeState(
    right: SwipeAction,
    left: SwipeAction,
): AnchoredDraggableState<SwipeSide> {
    val reach = with(LocalDensity.current) { SWIPE_REACH.toPx() }
    return remember(right, left, reach) {
        AnchoredDraggableState(
            SwipeSide.REST,
            DraggableAnchors {
                SwipeSide.REST at 0f
                if (right != SwipeAction.OFF) SwipeSide.START at reach
                if (left != SwipeAction.OFF) SwipeSide.END at -reach
            },
        )
    }
}

/** Swipes have to be reachable without a swipe (UI_DESIGN.md 7): each one is also an accessibility action. */
private fun Modifier.swipeActions(
    vararg actions: Pair<SwipeAction, String>,
    onSwipe: (SwipeAction) -> Unit,
) = semantics {
    customActions =
        actions
            .filter { (action, _) -> action != SwipeAction.OFF }
            .map { (action, label) -> CustomAccessibilityAction(label) { onSwipe(action).let { true } } }
}

/** Name, mute icon, network badge, folder tag, and time. */
@Composable
private fun TitleLine(
    row: ChatRow,
    now: Instant,
) {
    val unread = row.isUnread
    val colours = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            row.title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (unread) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.chat.isMuted) {
            Icon(
                painterResource(UiR.drawable.ic_notifications_off),
                stringResource(R.string.inbox_muted),
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NetworkBadge(row.network, row.last?.transport)
        if (row.chat.folder == ChatFolder.GENERAL) FolderTag(stringResource(R.string.inbox_folder_general))
        row.last?.let {
            Text(
                timeLabel(it.sentAt, now, stringResource(R.string.inbox_now)),
                style = MaterialTheme.typography.labelMedium,
                color = if (unread) colours.primary else colours.onSurfaceVariant,
            )
        }
    }
}

/** A tick of haptics when a swipe crosses the point where letting go acts (UI_DESIGN.md 4.5). */
@Composable
private fun SwipeFeedback(
    state: AnchoredDraggableState<SwipeSide>,
    haptics: Haptics,
) {
    val feedback = LocalHapticFeedback.current
    LaunchedEffect(state, haptics) {
        if (haptics == Haptics.OFF) return@LaunchedEffect
        snapshotFlow { state.targetValue }.distinctUntilChanged().drop(1).collect { target ->
            if (target != SwipeSide.REST) {
                feedback.performHapticFeedback(
                    if (haptics ==
                        Haptics.STRONG
                    ) {
                        HapticFeedbackType.LongPress
                    } else {
                        HapticFeedbackType.GestureThresholdActivate
                    },
                )
            }
        }
    }
}

@Composable
private fun RowContent(
    row: ChatRow,
    now: Instant,
) {
    val appearance = PingMeTheme.appearance
    val vertical =
        when (appearance.inboxDensity) {
            InboxDensity.COMPACT -> 4.dp
            InboxDensity.COMFORTABLE -> 9.dp
            InboxDensity.SPACIOUS -> 14.dp
        }
    val unread = row.isUnread
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            Avatar(row.title, size = appearance.avatarSize.dp)
            if (row.typing) TypingDots(Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 4.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TitleLine(row, now)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Your last message's mark, the same as in the chat (UI_DESIGN.md 3.1).
                val mine = row.last?.takeIf { it.isOutgoing && !row.typing && !row.chat.isObscured }
                if (mine != null) StatusMark(mine.status)
                Text(
                    if (row.typing) stringResource(R.string.inbox_typing) else previewText(row),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) colours.onSurface else colours.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unread) UnreadBadge(muted = row.chat.isMuted)
            }
        }
    }
}

/** The coloured layer under a swiped row, naming what letting go will do. */
@Composable
private fun BoxScope.SwipeReveal(
    action: SwipeAction,
    row: ChatRow,
    alignStart: Boolean,
) {
    val look = swipeLook(action, row)
    Row(
        Modifier
            .matchParentSize()
            .background(look.colour)
            .padding(horizontal = 24.dp),
        horizontalArrangement = if (alignStart) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(look.icon), null, tint = look.content)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(look.label),
            color = look.content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

private class SwipeLook(
    val colour: Color,
    val content: Color,
    val icon: Int,
    val label: Int,
)

@Composable
private fun swipeLook(
    action: SwipeAction,
    row: ChatRow,
): SwipeLook {
    val c = MaterialTheme.colorScheme
    return when (action) {
        SwipeAction.PIN -> {
            SwipeLook(
                c.secondary,
                c.onSecondary,
                UiR.drawable.ic_push_pin,
                if (row.chat.isPinned) R.string.action_unpin else R.string.action_pin,
            )
        }

        SwipeAction.ARCHIVE -> {
            SwipeLook(c.tertiary, c.onTertiary, UiR.drawable.ic_archive, R.string.action_archive)
        }

        SwipeAction.MUTE -> {
            SwipeLook(
                c.inverseSurface,
                c.inverseOnSurface,
                UiR.drawable.ic_notifications_off,
                if (row.chat.isMuted) R.string.action_unmute else R.string.action_mute,
            )
        }

        SwipeAction.MARK_READ -> {
            SwipeLook(
                c.primary,
                c.onPrimary,
                if (row.isUnread) UiR.drawable.ic_mark_chat_read else UiR.drawable.ic_mark_chat_unread,
                if (row.isUnread) R.string.action_read else R.string.action_unread,
            )
        }

        SwipeAction.LOW_PRIORITY -> {
            SwipeLook(
                c.secondaryContainer,
                c.onSecondaryContainer,
                UiR.drawable.ic_low_priority,
                R.string.action_low_priority,
            )
        }

        SwipeAction.DELETE -> {
            SwipeLook(c.error, c.onError, UiR.drawable.ic_delete, R.string.action_delete)
        }

        SwipeAction.OFF -> {
            SwipeLook(Color.Transparent, Color.Transparent, UiR.drawable.ic_close, R.string.fab_close)
        }
    }
}

/**
 * Turns the row over to show [emoji] large for 900 ms, then back (UI_DESIGN.md 10.8,
 * BUILD_PLAN.md P2.3). The inbox only sends reactions while Flippy reactions is on; with
 * motion off nothing turns.
 */
@Composable
private fun FlipCard(
    emoji: String?,
    onDone: () -> Unit,
    front: @Composable () -> Unit,
) {
    val flips = PingMeTheme.motion != MotionIntensity.OFF
    val turn = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(emoji) {
        if (emoji == null) return@LaunchedEffect
        if (flips) {
            turn.animateTo(HALF_TURN, tween(FLIP_MS))
            delay(SHOW_MS)
            turn.animateTo(0f, tween(FLIP_MS))
        }
        done()
    }
    val showingBack = turn.value > QUARTER_TURN
    Box(
        Modifier.graphicsLayer {
            rotationX = turn.value
            cameraDistance = CAMERA * density
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { alpha = if (showingBack) 0f else 1f }) { front() }
        if (showingBack && emoji != null) {
            Text(emoji, Modifier.graphicsLayer { rotationX = HALF_TURN }, fontSize = 34.sp)
        }
    }
}

private val SWIPE_REACH = 112.dp
private const val THRESHOLD = 0.6f
private const val FLIP_MS = 220
private const val SHOW_MS = 900L
private const val HALF_TURN = 180f
private const val QUARTER_TURN = 90f
private const val CAMERA = 12f
