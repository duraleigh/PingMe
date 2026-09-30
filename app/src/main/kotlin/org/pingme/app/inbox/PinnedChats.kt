// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * Pinned chats as a grid, five to a row and wrapping (BUILD_PLAN.md P2.3), each tile in
 * the shape family's pinned shape with its unread badge and typing dots.
 */
@Composable
fun PinnedGrid(
    pinned: List<ChatRow>,
    onOpen: (ChatRow) -> Unit,
    onHold: (ChatRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pinned.withIndex().chunked(PER_ROW).forEach { line ->
            Row {
                line.forEach { (index, row) ->
                    PinnedTile(row, index, { onOpen(row) }, { onHold(row) }, Modifier.weight(1f))
                }
                // Keep the last line's tiles the same width as the full lines above.
                repeat(PER_ROW - line.size) { Spacer(Modifier.weight(1f)) }
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
    Column(
        modifier.combinedClickable(role = Role.Button, onLongClickLabel = hold, onLongClick = onHold, onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box {
            Avatar(row.title, size = TILE_AVATAR, shape = PingMeTheme.shapes.pinnedTile(index))
            if (row.isUnread) {
                UnreadBadge(
                    row.chat.unreadCount,
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp),
                    muted = row.chat.isMuted,
                )
            }
            if (row.typing) TypingDots(Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 4.dp))
        }
        Text(
            // People by first name, groups by their whole name (cut short when long).
            if (row.chat.kind == ChatKind.GROUP) row.title else row.title.substringBefore(' '),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (row.isUnread) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

private const val PER_ROW = 5
private val TILE_AVATAR = 54.dp
private val TILE_WIDTH = 72.dp
