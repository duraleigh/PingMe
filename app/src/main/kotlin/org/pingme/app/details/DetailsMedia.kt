// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.pingme.app.R
import org.pingme.app.chat.attach.iconFor
import org.pingme.app.chat.search.SearchType
import org.pingme.core.model.Message
import java.io.File

/**
 * Media, links, and files from this chat (UI_DESIGN.md 3.4): the newest few of each, and
 * "See all" opens search in chat on that type. Tapping one opens it in the chat.
 */
@Composable
fun DetailsMedia(
    state: ChatDetailsState,
    onSeeAll: (SearchType) -> Unit,
    onOpen: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(R.string.details_media, R.string.details_links, R.string.details_files)
    val shown = listOf(state.media, state.links, state.files)[tab]
    Column(modifier.fillMaxWidth()) {
        PrimaryTabRow(tab) {
            tabs.forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(stringResource(label)) }) }
        }
        when {
            shown.isEmpty() -> Text(stringResource(R.string.details_nothing_yet), Modifier.padding(16.dp))
            tab == 0 -> Grid(shown, onOpen)
            else -> shown.forEach { Line(it, onOpen) }
        }
        if (shown.isNotEmpty()) {
            val type = listOf(SearchType.PHOTOS, SearchType.LINKS, SearchType.FILES)[tab]
            TextButton({ onSeeAll(type) }, Modifier.align(Alignment.End).padding(end = 8.dp)) {
                Text(stringResource(R.string.details_see_all))
            }
        }
    }
}

// Inside a scrolling list, so a fixed set of tiles rather than a lazy grid.
@Composable
private fun Grid(
    messages: List<Message>,
    onOpen: (Message) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        messages.forEach { message ->
            Box(
                Modifier
                    .width(TILE)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(message) },
            ) {
                AsyncImage(
                    message.attachments
                        .firstOrNull()
                        ?.localPath
                        ?.let(::File),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
private fun Line(
    message: Message,
    onOpen: (Message) -> Unit,
) {
    val file = message.attachments.firstOrNull()
    ListItem(
        headlineContent = {
            Text(file?.fileName ?: message.body.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = file?.let { { Icon(painterResource(iconFor(it.kind)), null) } },
        modifier = Modifier.clickable { onOpen(message) },
    )
}

private val TILE = 104.dp
