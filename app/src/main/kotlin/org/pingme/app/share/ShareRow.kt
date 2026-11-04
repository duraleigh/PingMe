// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.pingme.app.inbox.NetworkBadge
import org.pingme.app.inbox.previewText
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.components.GroupAvatar

/**
 * One place to share to, as Google Messages draws it: rows joined into one rounded card, the
 * picture, the name over the last message (or the number), and a round mark at the end.
 */
@Composable
internal fun ShareRow(
    target: ShareTarget,
    picked: Boolean,
    first: Boolean,
    last: Boolean,
    onToggle: (ShareTarget) -> Unit,
) {
    val top = if (first) CARD_CORNER else JOIN_CORNER
    val bottom = if (last) CARD_CORNER else JOIN_CORNER
    Surface(
        Modifier
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(top, top, bottom, bottom))
            .toggleable(picked, role = Role.Checkbox) { onToggle(target) },
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ListItem(
            leadingContent = { Picture(target) },
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        target.title,
                        Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Google Messages is the phone's own texting: its rows carry no badge, as in the inbox.
                    if (target.network != NetworkId.GMESSAGES) NetworkBadge(target.network)
                }
            },
            supportingContent =
                secondLine(target)?.let { line ->
                    { Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
            trailingContent = { RadioButton(picked, onClick = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun Picture(target: ShareTarget) =
    when (target) {
        is ShareTarget.ToChat -> {
            val row = target.row
            if (row != null && row.faces.isNotEmpty()) {
                GroupAvatar(row.faces, target.title, size = PICTURE)
            } else {
                Avatar(target.title, size = PICTURE, photo = row?.photo)
            }
        }

        is ShareTarget.ToPerson -> {
            Avatar(target.title, size = PICTURE, photo = target.person.photo)
        }
    }

/** The chat's last message, or the person's number or username; never a network's raw id. */
@Composable
private fun secondLine(target: ShareTarget): String? =
    when (target) {
        is ShareTarget.ToChat -> target.row?.let { previewText(it) }
        is ShareTarget.ToPerson -> target.person.networkHandle.takeIf { it.isNotBlank() && '@' !in it }
    }

private val PICTURE = 48.dp
private val CARD_CORNER = 24.dp
private val JOIN_CORNER = 4.dp
