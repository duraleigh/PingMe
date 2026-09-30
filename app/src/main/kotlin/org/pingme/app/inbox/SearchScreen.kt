// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import org.pingme.app.R
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.ui.components.Avatar
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.pingme.core.ui.R as UiR

/** What search found: chats by name, then messages by text. */
data class SearchResults(
    val chats: List<Chat> = emptyList(),
    val messages: List<Pair<Message, Chat>> = emptyList(),
    val now: Instant = Instant.DISTANT_PAST,
)

/**
 * Search across every network (DESIGN.md milestone 4, the inbox's search button): chat
 * names, and message text through the full-text index. All on the phone.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        chats: ChatRepository,
        messages: MessageRepository,
        clock: Clock,
        private val saved: SavedStateHandle,
    ) : ViewModel() {
        val query: StateFlow<String> = saved.getStateFlow(QUERY, "")

        val results: StateFlow<SearchResults> =
            query
                .debounce(TYPING_PAUSE)
                .flatMapLatest { text ->
                    val q = text.trim()
                    if (q.isEmpty()) {
                        flowOf(SearchResults())
                    } else {
                        combine(chats.all(), messages.search(q)) { all, found ->
                            // Message requests stay out of search, as they stay out of the inbox.
                            val visible = all.filter { it.folder != ChatFolder.REQUESTS }
                            val byId = visible.associateBy { it.id }
                            SearchResults(
                                chats = visible.filter { (it.nameOverride ?: it.title).contains(q, ignoreCase = true) },
                                messages =
                                    found
                                        .mapNotNull { m ->
                                            byId[m.chatId]?.let { m to it }
                                        }.filterNot { (_, c) -> c.isObscured },
                                now = clock.now(),
                            )
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), SearchResults())

        fun setQuery(text: String) {
            saved[QUERY] = text
        }

        private companion object {
            const val QUERY = "query"
            const val STOP_AFTER = 5_000L
            val TYPING_PAUSE = 150.milliseconds
        }
    }

@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onOpenChat: (ChatId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    SearchScreen(query, results, viewModel::setQuery, onBack, onOpenChat, modifier)
}

@Composable
fun SearchScreen(
    query: String,
    results: SearchResults,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onOpenChat: (ChatId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(modifier, topBar = {
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
            ) { Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back)) }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
    }) { padding -> Results(query, results, onOpenChat, padding) }
}

@Composable
private fun Results(
    query: String,
    results: SearchResults,
    onOpenChat: (ChatId) -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        if (results.chats.isNotEmpty()) item { SectionLabel(stringResource(R.string.search_chats)) }
        items(results.chats, key = { "c" + it.id.value }) { chat ->
            val name = chat.nameOverride ?: chat.title
            ListItem(
                onClick = { onOpenChat(chat.id) },
                leadingContent = { Avatar(name, size = 40.dp) },
            ) { Text(name) }
        }
        if (results.messages.isNotEmpty()) item { SectionLabel(stringResource(R.string.search_messages)) }
        items(results.messages, key = { "m" + it.first.id.value }) { (message, chat) ->
            val name = chat.nameOverride ?: chat.title
            ListItem(
                onClick = { onOpenChat(chat.id) },
                leadingContent = { Avatar(name, size = 40.dp) },
                supportingContent = {
                    Text(
                        message.body.orEmpty(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Text(
                        timeLabel(message.sentAt, results.now, stringResource(R.string.inbox_now)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            ) { Text(name) }
        }
        if (query.isNotBlank() && results.chats.isEmpty() && results.messages.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.search_nothing, query.trim()),
                    Modifier.padding(40.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
