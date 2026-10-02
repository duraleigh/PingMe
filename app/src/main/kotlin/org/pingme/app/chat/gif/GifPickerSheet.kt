// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.gif

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.pingme.app.R
import java.io.File
import org.pingme.core.ui.R as UiR

/**
 * The GIF sheet (UI_DESIGN.md 5.5): search, Trending, and favourites. Tap sends; hold keeps
 * a GIF as a favourite (or lets one go). Online results carry the provider's credit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifPickerSheet(
    search: GifSearch,
    picks: GifPicks,
    onDismiss: () -> Unit,
) {
    val state by search.state.collectAsStateWithLifecycle()
    LaunchedEffect(search) { search.open() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(SHEET_HEIGHT)) {
            OutlinedTextField(
                search.typed,
                search::search,
                Modifier.fillMaxWidth(),
                enabled = state.online,
                placeholder = {
                    Text(stringResource(if (state.online) R.string.gif_search else R.string.gif_search_off))
                },
                leadingIcon = { Icon(painterResource(UiR.drawable.ic_search), null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = MaterialTheme.shapes.extraLarge,
            )
            if (state.query.isBlank()) {
                PrimaryTabRow(state.tab.ordinal) {
                    Tab(state.tab == GifTab.FAVOURITES, { search.showTab(GifTab.FAVOURITES) }, text = {
                        Text(stringResource(R.string.gif_favourites))
                    })
                    Tab(state.tab == GifTab.TRENDING, {
                        search.showTab(
                            GifTab.TRENDING,
                        )
                    }, enabled = state.online, text = {
                        Text(stringResource(R.string.gif_trending))
                    })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (state.query.isBlank() && state.tab == GifTab.FAVOURITES) {
                    Favourites(state.favourites, picks.onFavourite, search::removeFavourite)
                } else {
                    Results(state, search, picks.onGif)
                }
            }
            if (state.online && (state.query.isNotBlank() || state.tab == GifTab.TRENDING)) {
                Text(
                    state.attribution,
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Results(
    state: GifPickerState,
    search: GifSearch,
    onGif: (Gif) -> Unit,
) {
    val grid = rememberLazyStaggeredGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last =
                grid.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - MORE_AHEAD
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) search.more() }
    when {
        state.results.isEmpty() && state.loading -> {
            Centered { LoadingIndicator() }
        }

        state.results.isEmpty() && state.failed -> {
            Centered { Text(stringResource(R.string.gif_failed)) }
        }

        state.results.isEmpty() && state.query.isNotBlank() -> {
            Centered { Text(stringResource(R.string.gif_nothing)) }
        }

        else -> {
            LazyVerticalStaggeredGrid(
                StaggeredGridCells.Adaptive(CELL),
                Modifier.testTag(GIF_GRID),
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalItemSpacing = 6.dp,
            ) {
                val kept = state.favourites.map { it.name }.toSet()
                items(state.results, key = { it.id }) { gif ->
                    val favourite = search.store.fileName(gif) in kept
                    GifTile(gif, search.store, favourite, { onGif(gif) }) { search.toggleFavourite(gif) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GifTile(
    gif: Gif,
    store: GifStore,
    favourite: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
) {
    val file by produceState<File?>(null, gif.id) { value = store.preview(gif) }
    val ratio = if (gif.preview.height > 0) gif.preview.width.toFloat() / gif.preview.height else 1f
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(MIN_RATIO, MAX_RATIO))
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onTap, onLongClick = onHold),
    ) {
        AsyncImage(file, gif.title, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        if (favourite) {
            Icon(
                painterResource(UiR.drawable.ic_star),
                stringResource(R.string.gif_is_favourite),
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Favourites(
    files: List<File>,
    onPick: (File) -> Unit,
    onRemove: (File) -> Unit,
) {
    if (files.isEmpty()) {
        Centered { Text(stringResource(R.string.gif_no_favourites), textAlign = TextAlign.Center) }
        return
    }
    LazyVerticalStaggeredGrid(
        StaggeredGridCells.Adaptive(CELL),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalItemSpacing = 6.dp,
    ) {
        items(files, key = { it.path }) { file ->
            AsyncImage(
                file,
                stringResource(R.string.gif_favourite),
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { onPick(file) }, onLongClick = { onRemove(file) }),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { content() }
}

const val GIF_GRID = "gif-grid"
private val SHEET_HEIGHT = 560.dp
private val CELL = 120.dp
private const val MORE_AHEAD = 6
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2.5f
