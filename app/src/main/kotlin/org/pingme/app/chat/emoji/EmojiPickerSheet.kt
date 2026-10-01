// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.emoji

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.core.ui.R as UiR

/**
 * The full emoji picker behind the reaction bar's "+" (UI_DESIGN.md 5.4): search, recent,
 * every group, and skin tones on long press. With [allowed] set (Telegram without Premium),
 * the rest show greyed and say why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(
    recent: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    allowed: Set<String>? = null,
    notAllowedReason: String = "",
) {
    val context = LocalContext.current
    val catalog = remember { context.assets.open(EmojiCatalog.ASSET).use(EmojiCatalog::read) }
    var query by remember { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val found = remember(query) { catalog.search(query) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 12.dp).height(SHEET_HEIGHT)) {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.emoji_search)) },
                leadingIcon = { Icon(painterResource(UiR.drawable.ic_search), null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
            if (query.isBlank()) {
                val starts = remember(catalog, recent) { groupStarts(catalog, recent.size) }
                // Only redraws the tabs when the group changes, not on every scrolled pixel.
                val current by remember(starts) { derivedStateOf { currentGroup(grid.firstVisibleItemIndex, starts) } }
                PrimaryScrollableTabRow(
                    selectedTabIndex = current,
                    edgePadding = 0.dp,
                ) {
                    catalog.groups.forEachIndexed { i, group ->
                        Tab(
                            selected = current == i,
                            onClick = { scope.launch { grid.scrollToItem(starts[i]) } },
                            text = { Text(group.name, maxLines = 1) },
                        )
                    }
                }
            }
            val pick = { emoji: String -> if (allowed == null || emoji in allowed) onPick(emoji) }
            LazyVerticalGrid(GridCells.Adaptive(CELL), state = grid, modifier = Modifier.fillMaxWidth()) {
                if (query.isNotBlank()) {
                    items(found, key = { it.value }) { EmojiCell(it, allowed, notAllowedReason, pick) }
                } else {
                    if (recent.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Heading(stringResource(R.string.emoji_recent)) }
                        items(
                            recent,
                            key = { "recent-$it" },
                        ) { EmojiCell(Emoji(it, it), allowed, notAllowedReason, pick) }
                    }
                    catalog.groups.forEach { group ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "group-${group.name}") { Heading(group.name) }
                        items(group.emoji, key = { it.value }) { EmojiCell(it, allowed, notAllowedReason, pick) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
}

/** One emoji; a long press offers its skin tones. Not allowed ones are greyed with the reason. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(
    emoji: Emoji,
    allowed: Set<String>?,
    reason: String,
    onPick: (String) -> Unit,
) {
    var tones by remember { mutableStateOf(false) }
    val usable = allowed == null || emoji.value in allowed
    Box(
        Modifier
            .size(CELL)
            .alpha(if (usable) 1f else GREYED)
            .semantics {
                contentDescription = emoji.name
                if (!usable) stateDescription = reason
            }.combinedClickable(
                onLongClick = { if (emoji.tones.isNotEmpty()) tones = true },
                onClick = { onPick(emoji.value) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji.value, fontSize = EMOJI_SIZE)
        DropdownMenu(expanded = tones, onDismissRequest = { tones = false }) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                (listOf(emoji.value) + emoji.tones).forEach { tone ->
                    Box(
                        Modifier.size(CELL).combinedClickable(onClick = {
                            tones = false
                            onPick(tone)
                        }),
                        contentAlignment = Alignment.Center,
                    ) { Text(tone, fontSize = EMOJI_SIZE) }
                }
            }
        }
    }
}

/** Where each group's heading sits in the grid, after the Recent heading and its emoji. */
internal fun groupStarts(
    catalog: EmojiCatalog,
    recentCount: Int,
): List<Int> {
    var index = if (recentCount > 0) 1 + recentCount else 0
    return catalog.groups.map { group ->
        val start = index
        index += 1 + group.emoji.size
        start
    }
}

private fun currentGroup(
    firstVisible: Int,
    starts: List<Int>,
) = starts.indexOfLast { it <= firstVisible }.coerceAtLeast(0)

private val CELL = 44.dp
private val EMOJI_SIZE = 26.sp
private val SHEET_HEIGHT = 480.dp
private const val GREYED = 0.35f
