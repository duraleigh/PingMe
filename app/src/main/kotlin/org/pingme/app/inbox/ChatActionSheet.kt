// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.R as UiR

/**
 * Press and hold on a row or pinned tile (UI_DESIGN.md 3.1): Pin, Mark read, Mute,
 * Archive, Low priority, Obscure, Delete. Each one says what it will do now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatActionSheet(
    row: ChatRow,
    onAction: (ChatAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val chat = row.chat
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (row.faces.isEmpty()) {
                    Avatar(row.title, size = 40.dp, photo = row.photo)
                } else {
                    org.pingme.core.ui.components
                        .GroupAvatar(row.faces, row.title, size = 40.dp)
                }
                Column {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(
                        row.network.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val items = sheetItems(row)
            items.forEach { (action, icon, label) ->
                SheetAction(icon, label, destructive = action == ChatAction.DELETE) {
                    onDismiss()
                    onAction(action)
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val colour = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        onClick = onClick,
        leadingContent = { Icon(painterResource(icon), null, tint = colour) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) { Text(stringResource(label), color = colour, fontWeight = FontWeight.SemiBold) }
}

/** Each action with the icon and label for what it will do to this chat now. */
private fun sheetItems(row: ChatRow): List<Triple<ChatAction, Int, Int>> {
    val chat = row.chat
    return listOf(
        Triple(
            ChatAction.PIN,
            UiR.drawable.ic_push_pin,
            if (chat.isPinned) R.string.action_unpin else R.string.action_pin,
        ),
        Triple(
            ChatAction.READ,
            if (row.isUnread) UiR.drawable.ic_mark_chat_read else UiR.drawable.ic_mark_chat_unread,
            if (row.isUnread) R.string.action_read else R.string.action_unread,
        ),
        Triple(
            ChatAction.MUTE,
            if (chat.isMuted) UiR.drawable.ic_notifications else UiR.drawable.ic_notifications_off,
            if (chat.isMuted) R.string.action_unmute else R.string.action_mute,
        ),
        Triple(
            ChatAction.ARCHIVE,
            if (chat.isArchived) UiR.drawable.ic_unarchive else UiR.drawable.ic_archive,
            if (chat.isArchived) R.string.action_unarchive else R.string.action_archive,
        ),
        Triple(
            ChatAction.LOW_PRIORITY,
            UiR.drawable.ic_low_priority,
            if (chat.isLowPriority) R.string.action_not_low_priority else R.string.action_low_priority,
        ),
        Triple(
            ChatAction.OBSCURE,
            if (chat.isObscured) UiR.drawable.ic_visibility else UiR.drawable.ic_visibility_off,
            if (chat.isObscured) R.string.action_unobscure else R.string.action_obscure,
        ),
        Triple(ChatAction.DELETE, UiR.drawable.ic_delete, R.string.action_delete),
    )
}
