// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.ui.components.PingMeSheet
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.toJavaInstant

/** Who placed which reaction, newest first; the user's own read "You". */
@Composable
internal fun ReactionsSheet(
    reactions: List<org.pingme.core.model.Reaction>,
    context: RowContext,
    onDismiss: () -> Unit,
) {
    PingMeSheet(onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.reactions_title),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            reactions.sortedByDescending { it.at }.forEach { reaction ->
                val name =
                    when {
                        reaction.senderId == context.me -> stringResource(R.string.you)
                        else -> context.names[reaction.senderId] ?: stringResource(R.string.reactions_someone)
                    }
                ListItem(
                    leadingContent = { Text(reaction.emoji, style = MaterialTheme.typography.headlineSmall) },
                    supportingContent = { Text(clockTime(reaction.at)) },
                ) { Text(name) }
            }
        }
    }
}

internal fun clockTime(at: kotlin.time.Instant): String =
    DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .format(at.toJavaInstant().atZone(ZoneId.systemDefault()))
