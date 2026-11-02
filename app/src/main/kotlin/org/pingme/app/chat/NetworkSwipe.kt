// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The order a swipe on the network chip goes through (owner, 2026-11-01): the person's default
 * first, then by network, and it stays put. The member list itself is newest-activity first,
 * so going by it would shuffle under the finger with every message.
 */
internal fun swipeOrder(members: List<MemberChip>): List<MemberChip> =
    members.sortedWith(
        compareByDescending<MemberChip> { it.isDefault }
            .thenBy { it.network.ordinal }
            .thenBy { it.label },
    )

/** The member a swipe lands on: left is the next one, right the one before, round the ends. */
internal fun nextMember(
    members: List<MemberChip>,
    current: MemberChip?,
    towardsNext: Boolean,
): MemberChip? {
    val order = swipeOrder(members)
    if (order.size < 2) return null
    val at = order.indexOf(current).coerceAtLeast(0)
    val step = if (towardsNext) 1 else order.size - 1
    return order[(at + step) % order.size]
}

/**
 * Flicking the chip moves to the next network without opening its menu; tapping still opens the
 * menu (owner, 2026-11-01). Any direction works: left or up is next, right or down is back (owner,
 * 2026-11-02: he had reached for up and down). The chip follows the finger, and a small buzz says
 * it took.
 */
@Composable
internal fun Modifier.swipeThroughNetworks(
    members: List<MemberChip>,
    current: MemberChip?,
    onPick: (MemberChip) -> Unit,
): Modifier {
    if (members.size < 2) return this
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val needed = with(LocalDensity.current) { SWIPE_DISTANCE.dp.toPx() }
    return this
        .graphicsLayer {
            translationX = shift.value.x
            translationY = shift.value.y
        }.pointerInput(members, current) {
            var dragged = Offset.Zero
            detectDragGestures(
                onDragStart = { dragged = Offset.Zero },
                onDragEnd = {
                    val distance = if (abs(dragged.x) >= abs(dragged.y)) dragged.x else dragged.y
                    if (abs(distance) >= needed) {
                        nextMember(members, current, towardsNext = distance < 0)?.let {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPick(it)
                        }
                    }
                    scope.launch { shift.animateTo(Offset.Zero) }
                },
                onDragCancel = { scope.launch { shift.animateTo(Offset.Zero) } },
            ) { change, amount ->
                change.consume()
                dragged += amount
                scope.launch {
                    shift.snapTo(
                        Offset(
                            (dragged.x * FOLLOW).coerceIn(-needed, needed),
                            (dragged.y * FOLLOW).coerceIn(-needed, needed),
                        ),
                    )
                }
            }
        }
}

// Barely past the touch slop, which already tells a tap from a move: a flick covers little ground.
private const val SWIPE_DISTANCE = 1
private const val FOLLOW = 0.5f
