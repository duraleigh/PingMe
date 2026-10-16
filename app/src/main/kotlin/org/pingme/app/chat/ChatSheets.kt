// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.inbox.displayName
import org.pingme.core.model.Capabilities
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.TimeLimit
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.components.PingMeSheet
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import org.pingme.core.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteSheet(
    count: Int,
    everyone: EveryoneDelete,
    onForMe: () -> Unit,
    onForEveryone: () -> Unit,
    onDismiss: () -> Unit,
) {
    PingMeSheet(onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                pluralStringResource(R.plurals.delete_title, count, count),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            ListItem(
                onClick = {
                    onDismiss()
                    onForMe()
                },
                leadingContent = { Icon(painterResource(UiR.drawable.ic_delete), null) },
            ) { Text(stringResource(R.string.delete_for_me)) }
            // Shown even when not possible, with the reason, rather than hidden (UI_DESIGN.md 1).
            ListItem(
                onClick = {
                    onDismiss()
                    onForEveryone()
                },
                enabled = everyone is EveryoneDelete.Allowed,
                leadingContent = {
                    Icon(
                        painterResource(UiR.drawable.ic_delete),
                        null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                supportingContent = { Text(everyoneReason(everyone)) },
            ) { Text(stringResource(R.string.delete_for_everyone), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun everyoneReason(everyone: EveryoneDelete): String =
    when (everyone) {
        is EveryoneDelete.Allowed -> {
            when (val limit = everyone.limit) {
                TimeLimit.Unlimited -> stringResource(R.string.delete_for_everyone_always)
                is TimeLimit.Within -> stringResource(R.string.delete_for_everyone_until, spoken(limit.duration))
            }
        }

        is EveryoneDelete.Expired -> {
            stringResource(R.string.delete_for_everyone_expired, spoken(everyone.limit.duration))
        }

        EveryoneDelete.NotYours -> {
            stringResource(R.string.delete_for_everyone_not_yours)
        }

        is EveryoneDelete.Unsupported -> {
            stringResource(R.string.delete_for_everyone_unsupported, everyone.network.displayName)
        }
    }

/** "1 hour", "15 minutes", "2 days": a time limit as people say it. */
@Composable
private fun spoken(duration: kotlin.time.Duration): String {
    val days = duration.inWholeDays.toInt()
    val hours = duration.inWholeHours.toInt()
    val minutes = duration.inWholeMinutes.toInt()
    return when {
        days >= 1 && duration.inWholeHours % HOURS_PER_DAY == 0L -> {
            pluralStringResource(
                R.plurals.duration_days,
                days,
                days,
            )
        }

        hours >= 1 && duration.inWholeMinutes % MINUTES_PER_HOUR == 0L -> {
            pluralStringResource(
                R.plurals.duration_hours,
                hours,
                hours,
            )
        }

        else -> {
            pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
        }
    }
}

private const val HOURS_PER_DAY = 24L
private const val MINUTES_PER_HOUR = 60L

/** Info from the action sheet: when it was sent, how far it got, and how it travelled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoSheet(
    message: Message,
    network: NetworkId,
    onDismiss: () -> Unit,
) {
    PingMeSheet(onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(
                stringResource(R.string.info_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            InfoLine(stringResource(R.string.info_sent), full(message.sentAt))
            if (!message.isOutgoing) InfoLine(stringResource(R.string.info_received), full(message.receivedAt))
            if (message.isOutgoing) InfoLine(stringResource(R.string.info_status), statusText(message.status))
            InfoLine(stringResource(R.string.info_network), "${network.displayName} · ${message.transport.name}")
            message.editedAt?.let { InfoLine(stringResource(R.string.info_edited), full(it)) }
        }
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun statusText(status: MessageStatus): String =
    when (status) {
        MessageStatus.Sending -> stringResource(R.string.status_sending)
        MessageStatus.Sent -> stringResource(R.string.status_sent)
        MessageStatus.Delivered -> stringResource(R.string.status_delivered)
        MessageStatus.Read -> stringResource(R.string.status_read)
        is MessageStatus.Failed -> stringResource(R.string.chat_not_sent, status.reason)
        is MessageStatus.Scheduled -> stringResource(R.string.status_scheduled) + " · " + full(status.at)
    }

private fun full(at: Instant) =
    DateTimeFormatter
        .ofLocalizedDateTime(
            FormatStyle.MEDIUM,
            FormatStyle.SHORT,
        ).format(at.toJavaInstant().atZone(ZoneId.systemDefault()))

/** Forward: pick one or more chats, then Send (UI_DESIGN.md 3.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardSheet(
    chats: List<Chat>,
    onSend: (List<ChatId>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(emptySet<ChatId>()) }
    PingMeSheet(onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.forward_title),
                Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            LazyColumn(Modifier.heightIn(max = LIST_HEIGHT)) {
                items(chats, key = { it.id.value }) { chat ->
                    val name = chat.nameOverride ?: chat.title
                    val on = chat.id in picked
                    ListItem(
                        onClick = { picked = if (on) picked - chat.id else picked + chat.id },
                        leadingContent = { Avatar(name, size = 40.dp) },
                        trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                    ) { Text(name) }
                }
            }
            Button(
                onClick = {
                    onDismiss()
                    onSend(picked.toList())
                },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) { Text(stringResource(R.string.forward_send)) }
        }
    }
}

private val LIST_HEIGHT = 420.dp
