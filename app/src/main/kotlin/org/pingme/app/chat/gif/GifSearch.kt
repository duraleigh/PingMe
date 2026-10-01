// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.gif

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** The GIF picker's two tabs; search results replace whichever is showing. */
enum class GifTab { FAVOURITES, TRENDING }

/** What the GIF picker shows. [online] is false when this build has no GIF search. */
data class GifPickerState(
    val tab: GifTab = GifTab.FAVOURITES,
    val query: String = "",
    val results: List<Gif> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** The last page came back short, so there is nothing more to ask for. */
    val ended: Boolean = false,
    val favourites: List<File> = emptyList(),
    val online: Boolean = false,
    val attribution: String = "",
)

/**
 * The GIF picker's search, trending, and favourites (UI_DESIGN.md 5.5). Nothing goes online
 * until the user types a search or opens Trending.
 */
class GifSearch(
    private val scope: CoroutineScope,
    val store: GifStore,
    /** Settings' "GIF search"; off leaves favourites only (UI_DESIGN.md 5.5). */
    enabled: Flow<Boolean> = flowOf(true),
) {
    private val built = store.provider
    private var provider = built
    private val now =
        MutableStateFlow(GifPickerState(online = built != null, attribution = built?.attribution.orEmpty()))
    private var loading: Job? = null

    init {
        scope.launch {
            enabled.collect { on ->
                provider = built?.takeIf { on }
                now.update {
                    it.copy(
                        online = provider != null,
                        tab =
                            if (provider ==
                                null
                            ) {
                                GifTab.FAVOURITES
                            } else {
                                it.tab
                            },
                    )
                }
            }
        }
    }

    val state: StateFlow<GifPickerState> = now.asStateFlow()

    /** Reads the favourites again; the picker calls this when it opens. */
    fun open() {
        scope.launch { now.update { it.copy(favourites = store.favourites()) } }
    }

    /**
     * What is in the search box. Held in Compose state, which the text box reads at once:
     * a box fed from a flow can be redrawn with an older value between keystrokes and
     * lose what was typed.
     */
    var typed by mutableStateOf("")
        private set

    fun showTab(tab: GifTab) {
        typed = ""
        now.update { it.copy(tab = tab, query = "", results = emptyList(), failed = false, ended = false) }
        if (tab == GifTab.TRENDING) load(fresh = true)
    }

    /** Searches after a short pause in typing; clearing the words goes back to the tab. */
    fun search(query: String) {
        typed = query
        now.update { it.copy(query = query) }
        if (query.isBlank()) {
            showTab(now.value.tab)
            return
        }
        load(fresh = true, wait = TYPING_PAUSE_MS)
    }

    /** The next page, when the grid nears its end. */
    fun more() {
        val current = now.value
        if (current.loading || current.ended || current.results.isEmpty()) return
        if (current.query.isBlank() && current.tab != GifTab.TRENDING) return
        load(fresh = false)
    }

    fun toggleFavourite(gif: Gif) {
        scope.launch {
            store.toggleFavourite(gif)
            now.update { it.copy(favourites = store.favourites()) }
        }
    }

    fun removeFavourite(file: File) {
        scope.launch {
            store.removeFavourite(file)
            now.update { it.copy(favourites = store.favourites()) }
        }
    }

    private fun load(
        fresh: Boolean,
        wait: Long = 0,
    ) {
        val source = provider ?: return
        loading?.cancel()
        loading =
            scope.launch {
                if (wait > 0) delay(wait)
                val query = now.value.query
                val offset = if (fresh) 0 else now.value.results.size
                now.update { it.copy(loading = true, failed = false) }
                val page =
                    runCatching {
                        if (query.isBlank()) source.trending(offset) else source.search(query, offset)
                    }
                now.update { state ->
                    val found = page.getOrNull()
                    state.copy(
                        loading = false,
                        failed = found == null,
                        ended = found != null && found.size < GiphyProvider.PAGE,
                        results =
                            if (fresh) {
                                found.orEmpty()
                            } else {
                                (state.results + found.orEmpty()).distinctBy {
                                    it.id
                                }
                            },
                    )
                }
            }
    }

    private companion object {
        const val TYPING_PAUSE_MS = 400L
    }
}
