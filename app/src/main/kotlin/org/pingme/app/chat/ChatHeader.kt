// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.inbox.NetworkBadge
import org.pingme.core.model.CallMethod
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.R as UiR

/**
 * The chat header (UI_DESIGN.md 3.2, 10.17): inset by the status bar only; avatar, name,
 * network badge and live status; phone, video, and overflow. Tapping the name opens Chat details.
 */
@Composable
fun ChatHeader(
    state: ChatUiState,
    actions: HeaderActions,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(
            Modifier
                .statusBarsPadding()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actions.onBack != null) {
                IconButton(onClick = actions.onBack) {
                    Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            TitleBlock(state, actions.onDetails, Modifier.weight(1f))
            CallButtons(state, actions.onCall)
            Overflow(actions)
        }
    }
}

@Composable
private fun TitleBlock(
    state: ChatUiState,
    onDetails: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val details = stringResource(R.string.chat_details)
    Row(
        modifier.then(
            if (onDetails !=
                null
            ) {
                Modifier.clickable(onClickLabel = details, role = Role.Button, onClick = onDetails)
            } else {
                Modifier
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(state.title, size = 42.dp)
        val colours = MaterialTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                state.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // No badge for Google Messages, the phone's own texting (owner, Gate G3).
                state.account
                    ?.takeIf { it.network != NetworkId.GMESSAGES }
                    ?.let { NetworkBadge(it.network) }
                Text(
                    liveStatus(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.typing.isEmpty()) colours.onSurfaceVariant else colours.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Sam is typing…", "Priya and Leo are typing…", or for groups how many are in it. */
@Composable
private fun liveStatus(state: ChatUiState): String {
    val chat = state.chat ?: return ""
    return when {
        state.typing.size == 1 && chat.kind == ChatKind.DIRECT -> {
            stringResource(R.string.inbox_typing)
        }

        state.typing.size == 1 -> {
            stringResource(R.string.chat_one_typing, state.typing.single().substringBefore(' '))
        }

        state.typing.size > 1 -> {
            stringResource(R.string.chat_many_typing)
        }

        chat.kind == ChatKind.GROUP -> {
            // Everyone else plus you; a network that lists you among the members is not counted twice
            // (owner, Gate G3: the count was one too many).
            val others = chat.participants.count { it != state.me && state.names[it] != YOU }
            pluralStringResource(R.plurals.chat_members, others + 1, others + 1)
        }

        else -> {
            ""
        }
    }
}

private const val YOU = "You"

/** RCS or SMS in a Google Messages chat follows the newest message. */
private fun ChatUiState.lastTransport() =
    items
        .firstNotNullOfOrNull {
            (it as? ChatItem.Bubble)?.message
        }?.let(Message::transport)

@Composable
private fun RowScope.CallButtons(
    state: ChatUiState,
    onCall: (Boolean) -> Unit,
) {
    val calls = state.capabilities?.calls ?: return
    if (calls.audio != CallMethod.NONE) {
        IconButton(onClick = {
            onCall(false)
        }) { Icon(painterResource(UiR.drawable.ic_call), stringResource(R.string.chat_call)) }
    }
    if (calls.video != CallMethod.NONE) {
        IconButton(onClick = {
            onCall(true)
        }) { Icon(painterResource(UiR.drawable.ic_videocam), stringResource(R.string.chat_video_call)) }
    }
}

@Composable
private fun Overflow(actions: HeaderActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = {
            open = true
        }) { Icon(painterResource(UiR.drawable.ic_more_vert), stringResource(R.string.chat_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_search)) },
                onClick = {
                    open = false
                    actions.onSearch?.invoke()
                },
                enabled = actions.onSearch != null,
                leadingIcon = { Icon(painterResource(UiR.drawable.ic_search), null, Modifier.size(20.dp)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_details)) },
                onClick = {
                    open = false
                    actions.onDetails?.invoke()
                },
                enabled = actions.onDetails != null,
                leadingIcon = { Icon(painterResource(UiR.drawable.ic_info), null, Modifier.size(20.dp)) },
            )
        }
    }
}
