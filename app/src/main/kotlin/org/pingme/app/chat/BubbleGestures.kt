// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.core.ui.theme.Haptics
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme
import kotlin.math.roundToInt
import org.pingme.core.ui.R as UiR

/** What touching a bubble does. */
class BubbleGestures(
    val onTap: () -> Unit,
    val onDoubleTap: () -> Unit,
    val onHold: () -> Unit,
    val onSwipeReply: () -> Unit,
)

/**
 * Wraps a message row: swipe right to reply (UI_DESIGN.md 5.2), double tap to react (10.5),
 * press and hold for the reaction bar and actions (3.3). [wobble] changes when a reaction
 * lands, and the bubble squashes and stretches (5.4).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageTouch(
    gestures: BubbleGestures,
    wobble: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reach = with(LocalDensity.current) { REPLY_REACH.toPx() }
    val drag = remember { Animatable(0f) }
    val squash = remember { Animatable(1f) }
    val haptic = LocalHapticFeedback.current
    val haptics = PingMeTheme.appearance.haptics
    val moving = PingMeTheme.motion != MotionIntensity.OFF
    val reply by rememberUpdatedState(gestures.onSwipeReply)
    LaunchedEffect(wobble) {
        if (wobble > 0 && moving) {
            squash.snapTo(SQUASH)
            squash.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
            )
        }
    }
    val replyLabel = stringResource(R.string.action_reply)
    val holdLabel = stringResource(R.string.inbox_chat_actions)
    Box(
        modifier
            .semantics {
                customActions =
                    listOf(CustomAccessibilityAction(replyLabel) { gestures.onSwipeReply().let { true } })
            }.swipeToReply(drag, reach, { reply() }) {
                if (haptics != Haptics.OFF) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            },
    ) {
        Icon(
            painterResource(UiR.drawable.ic_reply),
            null,
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .size(22.dp)
                .alpha((drag.value / reach).coerceIn(0f, 1f)),
            tint = MaterialTheme.colorScheme.primary,
        )
        Box(
            Modifier
                .offset { IntOffset(drag.value.roundToInt(), 0) }
                .graphicsLayer {
                    scaleX = 2f - squash.value
                    scaleY = squash.value
                }.combinedClickable(
                    onClick = gestures.onTap,
                    onDoubleClick = gestures.onDoubleTap,
                    onLongClick = gestures.onHold,
                    onLongClickLabel = holdLabel,
                ),
        ) { content() }
    }
}

/** Drags the row right; letting go past [reach] replies, and it springs back either way. */
private fun Modifier.swipeToReply(
    drag: Animatable<Float, *>,
    reach: Float,
    onReply: () -> Unit,
    onArmed: () -> Unit,
) = pointerInput(Unit) {
    var armed = false
    coroutineScope {
        detectHorizontalDragGestures(
            onDragEnd = {
                if (armed) onReply()
                armed = false
                launch { drag.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
            },
            onDragCancel = { launch { drag.animateTo(0f) } },
        ) { change, amount ->
            change.consume()
            val next = (drag.value + amount).coerceIn(0f, reach * OVERDRAG)
            launch { drag.snapTo(next) }
            if (!armed && next >= reach) {
                armed = true
                onArmed()
            }
        }
    }
}

private val REPLY_REACH = 64.dp
private const val OVERDRAG = 1.3f
private const val SQUASH = 0.9f
