// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.pingme.app.R
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.components.HapticPlayer
import org.pingme.core.ui.components.rememberHaptic
import org.pingme.core.ui.theme.PingMeTheme
import kotlin.math.roundToInt

/**
 * Pinned chats as a grid (BUILD_PLAN.md P2.3), each tile in the shape family's pinned
 * shape with its unread ring and typing dots. One or two pins sit centred; three to five
 * share the width; more wrap at five a line (owner, Gate G2). Press and hold a tile and
 * drag it to a new place to reorder (owner, 2026-10-05); let go without moving and the
 * chat's actions open as before.
 */
@Composable
fun PinnedGrid(
    pinned: List<ChatRow>,
    onOpen: (ChatRow) -> Unit,
    onHold: (ChatRow) -> Unit,
    modifier: Modifier = Modifier,
    onMove: (ChatId, Int) -> Unit = { _, _ -> },
) {
    val reorder = rememberPinReorder(pinned, onMove, onHold)
    val order = reorder.order
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (order.size <= CENTRED_UP_TO) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CENTRED_GAP, Alignment.CenterHorizontally),
            ) {
                order.forEachIndexed { index, row ->
                    PinnedTile(row, index, { onOpen(row) }, reorder, Modifier.width(CENTRED_WIDTH))
                }
            }
            return@Column
        }
        val perRow = minOf(order.size, PER_ROW)
        order.withIndex().chunked(perRow).forEach { line ->
            Row {
                line.forEach { (index, row) ->
                    PinnedTile(row, index, { onOpen(row) }, reorder, Modifier.weight(1f))
                }
                // Keep the last line's tiles the same width as the full lines above.
                repeat(perRow - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The "Row" pinned style (UI_DESIGN.md 3.1, 4.3): one line that scrolls sideways. */
@Composable
fun PinnedRow(
    pinned: List<ChatRow>,
    onOpen: (ChatRow) -> Unit,
    onHold: (ChatRow) -> Unit,
    modifier: Modifier = Modifier,
    onMove: (ChatId, Int) -> Unit = { _, _ -> },
) {
    val reorder = rememberPinReorder(pinned, onMove, onHold)
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        itemsIndexed(reorder.order, key = { _, row -> row.id.value }) { index, row ->
            PinnedTile(row, index, { onOpen(row) }, reorder, Modifier.width(TILE_WIDTH))
        }
    }
}

/**
 * One pinned chat. Unread, the tile has to shout as loudly as an unread row does (owner,
 * Gate G2: a dot alone was missed): the orbiting ring in the accent colour around the
 * avatar and the name in bold accent colour (the dot went on 2026-10-04).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedTile(
    row: ChatRow,
    index: Int,
    onOpen: () -> Unit,
    reorder: PinReorder,
    modifier: Modifier = Modifier,
) {
    val hold = stringResource(R.string.inbox_chat_actions)
    val unread = row.isUnread
    val shape = PingMeTheme.shapes.pinnedTile(index)
    val accent = if (row.chat.isMuted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val dragged = reorder.dragging == row.id
    Column(
        modifier
            .zIndex(if (dragged) 1f else 0f)
            .offset { if (dragged) reorder.offsetOf(index) else IntOffset.Zero }
            .onGloballyPositioned { reorder.slots[index] = it.boundsInRoot() }
            .semantics { customActions = listOf(CustomAccessibilityAction(hold) { reorder.onHold(row).let { true } }) }
            .pointerInput(row.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { reorder.start(row) },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.dragBy(amount)
                    },
                    onDragEnd = { reorder.end(row) },
                    onDragCancel = { reorder.end(row) },
                )
            }.combinedClickable(role = Role.Button, onLongClickLabel = hold, onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(TILE_AVATAR + (RING + RING_GAP) * 2), contentAlignment = Alignment.Center) {
            // Unread: the orbiting ring, no dot (owner, 2026-10-04).
            Ringed(unread, shape, gap = RING_GAP, muted = row.chat.isMuted) {
                if (row.faces.isEmpty()) {
                    Avatar(row.title, size = TILE_AVATAR, shape = shape, photo = row.photo)
                } else {
                    org.pingme.core.ui.components
                        .GroupAvatar(row.faces, row.title, size = TILE_AVATAR, shape = shape)
                }
            }
            if (row.typing) TypingDots(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 2.dp))
        }
        Text(
            // People by first name, groups by their whole name (cut short when long).
            if (row.chat.kind == ChatKind.GROUP) row.title else row.title.substringBefore(' '),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (unread) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (unread) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

private const val PER_ROW = 5
private const val CENTRED_UP_TO = 2
private val TILE_AVATAR = 54.dp
private val TILE_WIDTH = 72.dp
private val CENTRED_WIDTH = 84.dp
private val CENTRED_GAP = 24.dp
private val RING = 3.dp
private val RING_GAP = 3.dp

/**
 * The live order of the pinned tiles while one is being dragged: the dragged tile follows
 * the finger, the others make room as it crosses their slots, and letting go saves the new
 * place. A hold that never moves opens the chat's actions instead.
 */
internal class PinReorder(
    initial: List<ChatRow>,
    val onMove: (ChatId, Int) -> Unit,
    val onHold: (ChatRow) -> Unit,
    private val haptic: HapticPlayer,
) {
    val order = mutableStateListOf<ChatRow>().apply { addAll(initial) }

    /** Where each slot sits on screen, by its index in [order]. */
    val slots = mutableStateMapOf<Int, Rect>()
    var dragging by mutableStateOf<ChatId?>(null)
        private set
    private var startSlot = -1
    private var moved = false
    private var drag by mutableStateOf(Offset.Zero)

    fun start(row: ChatRow) {
        startSlot = order.indexOfFirst { it.id == row.id }
        if (startSlot < 0) return
        dragging = row.id
        drag = Offset.Zero
        moved = false
        haptic.bump()
    }

    fun dragBy(amount: Offset) {
        val id = dragging ?: return
        drag += amount
        val from = slots[startSlot] ?: return
        val finger = from.center + drag
        val target = slots.entries.firstOrNull { it.value.contains(finger) }?.key ?: return
        val current = order.indexOfFirst { it.id == id }
        if (target != current && target in order.indices) {
            order.add(target, order.removeAt(current))
            moved = true
            haptic.tick()
        }
    }

    fun end(row: ChatRow) {
        val id = dragging ?: return
        dragging = null
        if (moved) onMove(id, order.indexOfFirst { it.id == id }) else onHold(row)
    }

    /** How far the dragged tile, now in slot [index], sits from where the finger has taken it. */
    fun offsetOf(index: Int): IntOffset {
        val from = slots[startSlot] ?: return IntOffset.Zero
        val here = slots[index] ?: return IntOffset.Zero
        val shift = from.topLeft + drag - here.topLeft
        return IntOffset(shift.x.roundToInt(), shift.y.roundToInt())
    }
}

@Composable
internal fun rememberPinReorder(
    pinned: List<ChatRow>,
    onMove: (ChatId, Int) -> Unit,
    onHold: (ChatRow) -> Unit,
): PinReorder {
    val haptic = rememberHaptic()
    return remember(pinned, haptic) { PinReorder(pinned, onMove, onHold, haptic) }
}
