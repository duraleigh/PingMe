// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.ui.theme.SwipeAction
import org.pingme.core.ui.R as UiR

/** The coloured layer under a swiped row, naming what letting go will do. */
@Composable
internal fun BoxScope.SwipeReveal(
    action: SwipeAction,
    row: ChatRow,
    alignStart: Boolean,
) {
    val look = swipeLook(action, row)
    Row(
        Modifier
            .matchParentSize()
            .background(look.colour)
            .padding(horizontal = 24.dp),
        horizontalArrangement = if (alignStart) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(look.icon), null, tint = look.content)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(look.label),
            color = look.content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

internal class SwipeLook(
    val colour: Color,
    val content: Color,
    val icon: Int,
    val label: Int,
)

@Composable
internal fun swipeLook(
    action: SwipeAction,
    row: ChatRow,
): SwipeLook {
    val c = MaterialTheme.colorScheme
    return when (action) {
        SwipeAction.PIN -> {
            SwipeLook(
                c.secondary,
                c.onSecondary,
                UiR.drawable.ic_push_pin,
                if (row.chat.isPinned) R.string.action_unpin else R.string.action_pin,
            )
        }

        SwipeAction.ARCHIVE -> {
            SwipeLook(c.tertiary, c.onTertiary, UiR.drawable.ic_archive, R.string.action_archive)
        }

        SwipeAction.MUTE -> {
            SwipeLook(
                c.inverseSurface,
                c.inverseOnSurface,
                UiR.drawable.ic_notifications_off,
                if (row.chat.isMuted) R.string.action_unmute else R.string.action_mute,
            )
        }

        SwipeAction.MARK_READ -> {
            SwipeLook(
                c.primary,
                c.onPrimary,
                if (row.isUnread) UiR.drawable.ic_mark_chat_read else UiR.drawable.ic_mark_chat_unread,
                if (row.isUnread) R.string.action_read else R.string.action_unread,
            )
        }

        SwipeAction.LOW_PRIORITY -> {
            SwipeLook(
                c.secondaryContainer,
                c.onSecondaryContainer,
                UiR.drawable.ic_low_priority,
                R.string.action_low_priority,
            )
        }

        SwipeAction.DELETE -> {
            SwipeLook(c.error, c.onError, UiR.drawable.ic_delete, R.string.action_delete)
        }

        SwipeAction.MOVE_FOLDER -> {
            SwipeLook(
                c.tertiaryContainer,
                c.onTertiaryContainer,
                UiR.drawable.ic_archive,
                if (row.chat.folder == org.pingme.core.model.ChatFolder.GENERAL) {
                    R.string.action_move_primary
                } else {
                    R.string.action_move_general
                },
            )
        }

        SwipeAction.OFF -> {
            SwipeLook(Color.Transparent, Color.Transparent, UiR.drawable.ic_close, R.string.fab_close)
        }
    }
}
