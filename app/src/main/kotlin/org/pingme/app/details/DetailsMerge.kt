// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.inbox.NetworkBadge
import org.pingme.app.merge.ChatPickerSheet
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.ui.components.SettingsSectionHeader
import org.pingme.core.ui.R as UiR

/**
 * A merged chat's networks: each member with the network it is on, which one the composer
 * starts on, a way to split it off, and a way to add another chat. An ordinary one-to-one
 * chat offers "Merge with…" instead (UI_DESIGN.md 10.15; owner, Phase 7).
 */
@Composable
fun DetailsMerge(
    state: ChatDetailsState,
    choices: MergeChoices,
    modifier: Modifier = Modifier,
) {
    val chat = state.chat ?: return
    if (chat.kind != ChatKind.DIRECT) return
    var picking by remember { mutableStateOf(false) }
    Column(modifier) {
        if (state.members.isEmpty()) {
            OutlinedButton({ picking = true }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(painterResource(UiR.drawable.ic_call_merge), null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.merge_with))
            }
        } else {
            SettingsSectionHeader(stringResource(R.string.merge_members))
            Text(
                stringResource(R.string.merge_default),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            state.members.forEach { member -> MemberLine(member, choices) }
            TextButton({ picking = true }, Modifier.padding(horizontal = 8.dp)) {
                Icon(painterResource(UiR.drawable.ic_add), null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.merge_add_chat))
            }
        }
    }
    if (picking) {
        ChatPickerSheet(
            state.candidates,
            { picked -> if (state.members.isEmpty()) choices.onMergeWith(picked) else choices.onAddMembers(picked) },
        ) { picking = false }
    }
}

// A call button per network (UI_DESIGN.md 10.17; owner, Phase 7): each does the most direct thing its service allows.
@Composable
private fun MemberCalls(member: MemberRow) {
    val calls = member.calls ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val phone = member.person?.phoneNumber
    val dial =
        org.pingme.app.chat
            .Calls(context)
    androidx.compose.foundation.layout.Row {
        if (calls.audio != org.pingme.core.model.CallMethod.NONE) {
            IconButton({ dial.start(member.network, calls.audio, false, phone) }) {
                Icon(painterResource(UiR.drawable.ic_call), stringResource(R.string.chat_call))
            }
        }
        if (calls.video != org.pingme.core.model.CallMethod.NONE) {
            IconButton({ dial.start(member.network, calls.video, true, phone) }) {
                Icon(painterResource(UiR.drawable.ic_videocam), stringResource(R.string.chat_video_call))
            }
        }
    }
}

@Composable
private fun MemberLine(
    member: MemberRow,
    choices: MergeChoices,
) {
    val name = member.chat.nameOverride ?: member.chat.title
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement =
                    androidx.compose.foundation.layout.Arrangement
                        .spacedBy(8.dp),
            ) {
                NetworkBadge(member.network)
                val detail =
                    member.person?.let {
                        it.phoneNumber
                            ?: it.networkHandle.takeIf { h -> '@' !in h && h != it.name }?.let { h -> "@$h" }
                    }
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        leadingContent = {
            RadioButton(member.isDefault, onClick = null)
        },
        trailingContent = {
            androidx.compose.foundation.layout.Row {
                MemberCalls(member)
                IconButton({ choices.onSplit(member.chat.id) }) {
                    Icon(painterResource(UiR.drawable.ic_call_split), stringResource(R.string.merge_split))
                }
            }
        },
        modifier =
            Modifier.selectable(member.isDefault, role = Role.RadioButton) {
                choices.onDefault(member.chat.accountId)
            },
    )
}
