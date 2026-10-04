// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.app.appearance.Wallpaper
import org.pingme.app.chat.attach.AttachSheet
import org.pingme.app.chat.attach.SendButton
import org.pingme.app.chat.attach.StagedStrip
import org.pingme.app.chat.search.ChatSearchBar
import org.pingme.app.chat.search.SearchPeople
import org.pingme.app.inbox.NetworkBadge
import org.pingme.core.model.CallMethod
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

// The strips above and below the messages: the pinned banner, the reply strip, the composer.

/** The newest pinned message under the header; tap to jump to it (UI_DESIGN.md 3.2, 5.1). */
@Composable
internal fun PinnedBanner(
    message: Message,
    state: ChatUiState,
    onJump: () -> Unit,
    onUnpin: () -> Unit,
) {
    Surface(onClick = onJump, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(UiR.drawable.ic_keep),
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(
                    stringResource(R.string.chat_pinned),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    summary(message, state),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onUnpin,
            ) { Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.chat_unpin)) }
        }
    }
}

/** "Editing message" above the composer while one of your messages is being edited (UI_DESIGN.md 3.3). */
@Composable
internal fun EditStrip(
    message: Message,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
        modifier = Modifier.padding(horizontal = 12.dp),
    ) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    stringResource(R.string.editing),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    message.body.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onCancel,
            ) { Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.cancel_edit)) }
        }
    }
}

/** "Replying to Sam" with the message, above the composer (UI_DESIGN.md 5.2). */
@Composable
internal fun ReplyStrip(
    message: Message,
    state: ChatUiState,
    onCancel: () -> Unit,
) {
    val name = if (message.isOutgoing) stringResource(R.string.you) else state.names[message.senderId].orEmpty()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
        modifier = Modifier.padding(horizontal = 12.dp),
    ) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    stringResource(R.string.chat_replying_to, name),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    summary(message, state),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.chat_cancel_reply))
            }
        }
    }
}

@Composable
private fun summary(
    message: Message,
    state: ChatUiState,
): String =
    message.body?.takeIf { it.isNotBlank() }
        ?: org.pingme.app.inbox.previewText(
            org.pingme.app.inbox
                .ChatRow(state.chat!!, state.account!!.network, message.toLast(), typing = false),
        )

private fun Message.toLast() =
    org.pingme.core.store
        .LastMessage(body, kind, isOutgoing, transport, sentAt, null)

@Composable
internal fun TopBars(
    state: ChatUiState,
    actions: ChatScreenActions,
    onJump: (String) -> Unit,
) {
    if (state.selection.isNotEmpty()) {
        SelectionHeader(state.selection.size, { actions.menu?.clearSelection() })
        return
    }
    val search = actions.search.search
    if (search != null && actions.search.state.open) {
        val group = state.chat?.kind == org.pingme.core.model.ChatKind.GROUP
        ChatSearchBar(actions.search.state, search, SearchPeople(group, state.names), actions.search.onDate)
        return
    }
    Column {
        ChatHeader(state, actions.header)
        state.pinned.firstOrNull()?.let { PinnedBanner(it, state, { onJump(it.id.value) }) { actions.onUnpin(it) } }
    }
}

@Composable
internal fun BottomBars(
    state: ChatUiState,
    actions: ChatScreenActions,
    ui: ChatUi,
    context: android.content.Context,
) {
    Column(Modifier.navigationBarsPadding().imePadding()) {
        if (state.selection.isNotEmpty()) {
            val chosen =
                state.items.filterIsInstance<ChatItem.Bubble>().map { it.message }.filter {
                    it.id in
                        state.selection
                }
            SelectionToolbar(
                SelectionActions(
                    onCopy = {
                        copy(context, chosen)
                        actions.menu?.clearSelection()
                    },
                    onForward = { ui.forwarding = chosen },
                    onDelete = { ui.deleting = chosen },
                    onShare = { share(context, chosen) },
                ),
            )
            return@Column
        }
        val editing = state.editing
        if (editing != null) {
            EditStrip(editing) { actions.menu?.startEdit(null) }
        } else {
            state.replyTo?.let { ReplyStrip(it, state) { actions.onReply(null) } }
        }
        val send: (String) -> Unit =
            if (editing !=
                null
            ) {
                ({ text -> actions.menu?.finishEdit(text) })
            } else {
                actions.onSend
            }
        if (state.members.isNotEmpty() && editing == null) NetworkChips(state, actions.onSendVia)
        Composer(send, actions.onTyping, editing, actions.composer)
    }
}

/**
 * A merged chat's composer chips, one per member network (UI_DESIGN.md 10.15): the filled
 * one is where the next message goes; a disconnected account's chip is outlined in the
 * error colour. Tap one to switch.
 */
@Composable
private fun NetworkChips(
    state: ChatUiState,
    onSendVia: (ChatId) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val offline = stringResource(R.string.chat_network_disconnected)
        state.members.forEach { member ->
            val picked = member.chatId == state.sendVia
            val colours = MaterialTheme.colorScheme
            FilterChip(
                selected = picked,
                onClick = { onSendVia(member.chatId) },
                label = { Text(member.label) },
                leadingIcon = { NetworkBadge(member.network) },
                border =
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = picked,
                        borderColor = if (member.connected) colours.outline else colours.error,
                        selectedBorderColor = if (member.connected) colours.outline else colours.error,
                        selectedBorderWidth = if (member.connected) 0.dp else 1.dp,
                    ),
                colors =
                    FilterChipDefaults.filterChipColors(
                        labelColor = if (member.connected) colours.onSurface else colours.onSurfaceVariant,
                    ),
                modifier = Modifier.semantics { if (!member.connected) stateDescription = offline },
            )
        }
    }
}

/** Snackbars from message actions: Undo for deletes, the network's reason when it says no. */
@Composable
internal fun Notices(
    menu: MessageMenu?,
    snackbar: SnackbarHostState,
) {
    val resources = LocalResources.current
    LaunchedEffect(menu) {
        menu?.notices?.collect { notice ->
            launch {
                val result =
                    snackbar.showSnackbar(
                        noticeText(notice, resources),
                        actionLabel =
                            notice.undo?.let {
                                resources.getString(R.string.undo)
                            },
                    )
                if (result == SnackbarResult.ActionPerformed) notice.undo?.invoke() else notice.onGone()
            }
        }
    }
}

internal fun noticeText(
    notice: ChatNotice,
    resources: android.content.res.Resources,
): String =
    when {
        notice.plural != null -> resources.getQuantityString(notice.plural, notice.count, notice.count)
        notice.text != null && notice.reason != null -> resources.getString(notice.text, notice.reason)
        notice.text != null && notice.arg != null -> resources.getString(notice.text, notice.arg)
        notice.text != null -> resources.getString(notice.text)
        else -> notice.reason.orEmpty()
    }
