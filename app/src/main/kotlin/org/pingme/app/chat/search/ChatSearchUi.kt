// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.pingme.app.R
import org.pingme.app.chat.attach.firstFrame
import org.pingme.app.chat.attach.iconFor
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.model.PersonId
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant
import org.pingme.core.ui.R as UiR

/**
 * The header while searching (UI_DESIGN.md 10.14): the search field, the type chips, a
 * sender filter in groups, and a date jump.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSearchBar(
    state: ChatSearchState,
    search: ChatSearch,
    people: SearchPeople,
    onDate: (Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var picking by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.statusBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(search::close) {
                    Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.search_close))
                }
                TextField(
                    state.query,
                    search::query,
                    Modifier.weight(1f).focusRequester(focus).testTag(SEARCH_FIELD),
                    placeholder = { Text(stringResource(R.string.search_in_chat)) },
                    singleLine = true,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                )
            }
            LazyRow(
                Modifier.testTag(SEARCH_CHIPS),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchType.entries) { type ->
                    FilterChip(state.type == type, { search.type(type) }, label = { Text(stringResource(type.label)) })
                }
                if (people.group) item { SenderChip(state.sender, people.names, search::sender) }
                item {
                    AssistChip(
                        { picking = true },
                        label = { Text(stringResource(R.string.search_date)) },
                        leadingIcon = { Icon(painterResource(UiR.drawable.ic_schedule), null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
    }
    if (picking) DateJump({ picking = false }) { onDate(it) }
}

@Composable
private fun SenderChip(
    sender: PersonId?,
    names: Map<PersonId, String>,
    onPick: (PersonId?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        val label =
            sender?.let { stringResource(R.string.search_from, names[it].orEmpty()) }
                ?: stringResource(R.string.search_from_anyone)
        FilterChip(sender != null, { open = true }, label = { Text(label) })
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.search_from_anyone)) }, {
                onPick(null)
                open = false
            })
            names.entries.sortedBy { it.value }.forEach { (id, name) ->
                DropdownMenuItem({ Text(name) }, {
                    onPick(id)
                    open = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateJump(
    onDismiss: () -> Unit,
    onPick: (Instant) -> Unit,
) {
    val picker = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                picker.selectedDateMillis?.let { onPick(startOfDay(it)) }
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    ) { DatePicker(picker) }
}

/** The picker gives the day as midnight UTC; the jump starts at midnight where the phone is. */
internal fun startOfDay(utcMidnight: Long): Instant {
    val day =
        java.time.Instant
            .ofEpochMilli(utcMidnight)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
    return Instant.fromEpochMilliseconds(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
}

/**
 * What search in chat found (UI_DESIGN.md 10.14): photos, videos, and GIFs as a grid,
 * everything else as a list. Tapping one jumps to it in the chat.
 */
@Composable
fun ChatSearchResults(
    state: ChatSearchState,
    names: Map<PersonId, String>,
    onOpen: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize().testTag(SEARCH_RESULTS), color = MaterialTheme.colorScheme.surface) {
        when {
            state.results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.search_none)) }
            }

            state.type.grid -> {
                LazyVerticalGrid(GridCells.Adaptive(TILE), contentPadding = PaddingValues(2.dp)) {
                    items(state.results, key = { it.id.value }) { MediaTile(it) { onOpen(it) } }
                }
            }

            else -> {
                LazyColumn {
                    items(state.results, key = { it.id.value }) { ResultLine(it, names[it.senderId], onOpen) }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(
    message: Message,
    onClick: () -> Unit,
) {
    val path = message.attachments.firstOrNull()?.localPath
    val frame by produceState<ImageBitmap?>(null, path) {
        value = if (message.kind == MessageKind.VIDEO && path != null) firstFrame(path) else null
    }
    Box(Modifier.padding(2.dp).aspectRatio(1f).clickable(onClick = onClick)) {
        val still = frame
        if (still != null) {
            androidx.compose.foundation.Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            AsyncImage(path?.let(::File), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun ResultLine(
    message: Message,
    sender: String?,
    onOpen: (Message) -> Unit,
) {
    val file = message.attachments.firstOrNull()
    val text =
        message.body?.takeIf { it.isNotBlank() }
            ?: if (message.kind ==
                MessageKind.VOICE
            ) {
                stringResource(R.string.search_voice_note)
            } else {
                file?.fileName.orEmpty()
            }
    ListItem(
        headlineContent = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        overlineContent = { Text(listOfNotNull(sender, sentDay(message)).joinToString(" · ")) },
        leadingContent = file?.let { { Icon(painterResource(iconFor(it.kind)), null) } },
        modifier = Modifier.clickable { onOpen(message) },
    )
}

// The day a result was sent, in the phone's own date style.
private fun sentDay(message: Message): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .format(
            java.time.Instant
                .ofEpochMilli(message.sentAt.toEpochMilliseconds())
                .atZone(ZoneId.systemDefault()),
        )

const val SEARCH_FIELD = "chat-search"
const val SEARCH_RESULTS = "chat-search-results"
const val SEARCH_CHIPS = "chat-search-chips"
private val TILE = 110.dp
