// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.chat.emoji.EmojiPickerSheet
import org.pingme.app.inbox.displayName
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Message
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.components.SettingsSectionHeader
import org.pingme.core.ui.components.SwitchSetting
import org.pingme.core.ui.R as UiR

/**
 * This chat's quick reactions (UI_DESIGN.md 3.4, 5.4): the app's set, or its own set of up
 * to eight, edited by removing chips and adding from the full picker.
 */
@Composable
fun DetailsReactions(
    state: ChatDetailsState,
    onSet: (List<String>?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val own = state.overrides?.quickReactions
    val shown = own ?: state.appReactions
    var adding by remember { mutableStateOf(false) }
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.details_reactions))
        SwitchSetting(
            stringResource(R.string.details_reactions_own),
            own != null,
            { on -> onSet(if (on) state.appReactions else null) },
            description = if (own == null) stringResource(R.string.details_reactions_app) else null,
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shown.forEach { emoji ->
                InputChip(
                    selected = false,
                    onClick = { if (own != null) onSet(own - emoji) },
                    enabled = own != null,
                    label = { Text(emoji, style = MaterialTheme.typography.titleLarge) },
                    trailingIcon = {
                        if (own != null) {
                            Icon(
                                painterResource(UiR.drawable.ic_close),
                                stringResource(R.string.details_reactions_remove, emoji),
                            )
                        }
                    },
                )
            }
            if (own != null && own.size < MAX_REACTIONS) {
                AssistChip({ adding = true }, label = { Text(stringResource(R.string.details_reactions_add)) })
            }
        }
    }
    if (adding && own != null) {
        EmojiPickerSheet(recent = emptyList(), onPick = {
            if (it !in
                own
            ) {
                onSet(own + it)
            }
        }, onDismiss = { adding = false })
    }
}

/** Who is in a group (UI_DESIGN.md 3.4). */
@Composable
fun DetailsMembers(
    state: ChatDetailsState,
    modifier: Modifier = Modifier,
) {
    if (state.chat?.kind != org.pingme.core.model.ChatKind.GROUP || state.people.isEmpty()) return
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.details_members))
        state.people.forEach { person ->
            ListItem(
                headlineContent = { Text(person.displayName) },
                supportingContent = { Text(person.phoneNumber ?: person.networkHandle) },
                leadingContent = { Avatar(person.displayName, size = 40.dp) },
            )
        }
    }
}

/** The chat's pinned messages: tap one to see it in the chat, or unpin it (UI_DESIGN.md 5.1). */
@Composable
fun DetailsPinned(
    state: ChatDetailsState,
    onOpen: (Message) -> Unit,
    onUnpin: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.pinned.isEmpty()) return
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.details_pinned))
        state.pinned.forEach { message ->
            ListItem(
                headlineContent = {
                    Text(
                        message.body ?: message.attachments
                            .firstOrNull()
                            ?.fileName
                            .orEmpty(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { Icon(painterResource(UiR.drawable.ic_keep), null) },
                trailingContent = {
                    androidx.compose.material3.IconButton({ onUnpin(message) }) {
                        Icon(painterResource(UiR.drawable.ic_close), stringResource(R.string.action_unpin_message))
                    }
                },
                modifier = Modifier.clickable { onOpen(message) },
            )
        }
    }
}

/**
 * Which photo stands for this chat (UI_DESIGN.md 10.18): the contact's, or the network's
 * profile photo.
 */
@Composable
fun DetailsPhoto(
    state: ChatDetailsState,
    onPick: (AvatarSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = state.chat ?: return
    val account = state.account ?: return
    if (chat.kind != org.pingme.core.model.ChatKind.DIRECT) return
    val choices = listOf(AvatarSource.Contacts, AvatarSource.Network(account.id))
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.details_photo))
        choices.forEach { source ->
            val label =
                when (source) {
                    is AvatarSource.Network -> {
                        stringResource(
                            R.string.details_photo_network,
                            account.network.displayName,
                        )
                    }

                    else -> {
                        stringResource(R.string.details_photo_contact)
                    }
                }
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = { RadioButton(chat.avatarSource == source, onClick = null) },
                modifier = Modifier.selectable(chat.avatarSource == source, role = Role.RadioButton) { onPick(source) },
            )
        }
    }
}

private const val MAX_REACTIONS = 8
