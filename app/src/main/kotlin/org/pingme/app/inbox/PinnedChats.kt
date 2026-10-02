// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.model.ChatKind
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.theme.PingMeTheme

/**
 * Pinned chats as a grid (BUILD_PLAN.md P2.3), each tile in the shape family's pinned
 * shape with its unread ring and typing dots. One or two pins sit centred; three to five
 * share the width; more wrap at five a line (owner, Gate G2).
 */
@Composable
fun PinnedGrid(
    pinned: List<ChatRow>,
    onOpen: (ChatRow) -> Unit,
    onHold: (ChatRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pinned.size <= CENTRED_UP_TO) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CENTRED_GAP, Alignment.CenterHorizontally),
            ) {
                pinned.forEachIndexed { index, row ->
                    PinnedTile(row, index, { onOpen(row) }, { onHold(row) }, Modifier.width(CENTRED_WIDTH))
                }
            }
            return@Column
        }
        val perRow = minOf(pinned.size, PER_ROW)
        pinned.withIndex().chunked(perRow).forEach { line ->
            Row {
                line.forEach { (index, row) ->
                    PinnedTile(row, index, { onOpen(row) }, { onHold(row) }, Modifier.weight(1f))
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
) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        itemsIndexed(pinned, key = { _, row -> row.id.value }) { index, row ->
            PinnedTile(row, index, { onOpen(row) }, { onHold(row) }, Modifier.width(TILE_WIDTH))
        }
    }
}

/**
 * One pinned chat. Unread, the tile has to shout as loudly as an unread row does (owner,
 * Gate G2: a dot alone was missed): a thick ring in the accent colour around the avatar, a
 * big dot on its corner, and the name in bold accent colour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedTile(
    row: ChatRow,
    index: Int,
    onOpen: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hold = stringResource(R.string.inbox_chat_actions)
    val unread = row.isUnread
    val shape = PingMeTheme.shapes.pinnedTile(index)
    val accent = if (row.chat.isMuted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    Column(
        modifier.combinedClickable(role = Role.Button, onLongClickLabel = hold, onLongClick = onHold, onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(TILE_AVATAR + (RING + RING_GAP) * 2), contentAlignment = Alignment.Center) {
            if (unread) Box(Modifier.matchParentSize().border(RING, accent, shape.toShape()))
            Avatar(row.title, size = TILE_AVATAR, shape = shape)
            if (unread) {
                UnreadBadge(
                    Modifier.align(Alignment.TopEnd),
                    muted = row.chat.isMuted,
                    size = TILE_DOT,
                    outlined = true,
                )
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
private val TILE_DOT = 20.dp
