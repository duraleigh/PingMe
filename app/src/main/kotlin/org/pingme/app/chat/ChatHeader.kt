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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
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
import org.pingme.core.ui.theme.resized
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
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actions.onBack != null) {
                IconButton(onClick = actions.onBack) {
                    Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back))
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }
            TitleBlock(state, actions.onDetails, Modifier.weight(1f), actions.onProfile)
            CallIcon(state, actions.onCall)
            Overflow(state, actions)
        }
    }
}

@Composable
private fun TitleBlock(
    state: ChatUiState,
    onDetails: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onProfile: (() -> Unit)? = null,
) {
    val details = stringResource(R.string.chat_details)
    val profile = stringResource(R.string.chat_open_profile)
    // The avatar opens the person's Instagram page (owner, 2026-10-05); the name still opens details.
    val avatarModifier =
        if (onProfile != null) {
            Modifier.clickable(onClickLabel = profile, role = Role.Button, onClick = onProfile)
        } else {
            Modifier
        }
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.faces.isEmpty()) {
            Avatar(state.title, size = 40.dp, photo = state.photo, modifier = avatarModifier)
        } else {
            org.pingme.core.ui.components
                .GroupAvatar(state.faces, state.title, size = 40.dp)
        }
        // Just the name, as in Google Messages (owner, 2026-11-02): the network is on the chip in
        // the box, and a group's count is in its details.
        Text(
            state.title,
            // Measured against Google Messages on the phone: 330 wide where titleLarge made 389.
            style = MaterialTheme.typography.titleLarge.resized(TITLE_RATIO),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The phone icon; Video is in the menu, as in Google Messages (owner, 2026-11-02). */
@Composable
private fun CallIcon(
    state: ChatUiState,
    onCall: (Boolean) -> Unit,
) {
    val calls = state.capabilities?.calls ?: return
    val network = state.sendNetwork ?: state.account?.network ?: return
    if (calls.audio != CallMethod.NONE) CallButton(network, calls.audio, video = false) { onCall(false) }
}

/** A call icon; its long-press says what the tap does on this network (UI_DESIGN.md 10.17). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallButton(
    network: NetworkId,
    method: CallMethod,
    video: Boolean,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    val explanation = remember(network, method, video) { callExplanation(resources, network, method, video) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(explanation) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                painterResource(if (video) UiR.drawable.ic_videocam else UiR.drawable.ic_call),
                stringResource(if (video) R.string.chat_video_call else R.string.chat_call),
            )
        }
    }
}

@Composable
private fun Overflow(
    state: ChatUiState,
    actions: HeaderActions,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = {
            open = true
        }) { Icon(painterResource(UiR.drawable.ic_more_vert), stringResource(R.string.chat_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.capabilities
                    ?.calls
                    ?.video
                    ?.let { it != CallMethod.NONE } == true
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_video_call)) },
                    onClick = {
                        open = false
                        actions.onCall(true)
                    },
                    leadingIcon = { Icon(painterResource(UiR.drawable.ic_videocam), null, Modifier.size(20.dp)) },
                )
            }
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

// 330 wide on Google Messages where titleLarge made 389 (2026-11-02).
private const val TITLE_RATIO = 0.86f
