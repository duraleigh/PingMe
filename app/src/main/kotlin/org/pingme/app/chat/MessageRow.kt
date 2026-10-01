// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.pingme.app.R
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Message
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Transport
import org.pingme.core.ui.components.Avatar
import org.pingme.core.ui.components.MessageBubble
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.TimestampMode
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.toJavaInstant
import org.pingme.core.ui.R as UiR

/**
 * One message (UI_DESIGN.md 3.2): the bubble in its network's colour, with the sender's name
 * on the first of a group in group chats, a reply quote, pictures, the text, a link card,
 * the time and status, reaction chips, and a retry for failed sends.
 */
@Composable
fun MessageRow(
    item: ChatItem.Bubble,
    context: RowContext,
    modifier: Modifier = Modifier,
    bubbleModifier: Modifier = Modifier,
    showTime: Boolean = true,
) {
    val message = item.message
    val outgoing = message.isOutgoing
    val appearance = PingMeTheme.appearance
    val sender = context.names[message.senderId]
    val showAvatar = !outgoing && context.kind == ChatKind.GROUP && appearance.showAvatarsInChat
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (item.lastInGroup) 3.dp else 1.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showAvatar) {
            if (item.lastInGroup) Avatar(sender.orEmpty(), size = AVATAR) else Spacer(Modifier.width(AVATAR))
            Spacer(Modifier.width(8.dp))
        }
        Column(bubbleModifier, horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
            MessageBubble(
                text = bodyText(message),
                outgoing = outgoing,
                network = context.network,
                transport = message.transport,
                lastInGroup = item.lastInGroup,
                header = { BubbleTop(item, context, sender) },
                footer = {
                    message.linkPreview?.let { LinkCard(it) }
                    if (showTime || message.status is MessageStatus.Failed ||
                        message.status is MessageStatus.Scheduled
                    ) {
                        Footer(message)
                    }
                },
            )
            if (message.reactions.isNotEmpty()) Reactions(message)
            (message.status as? MessageStatus.Failed)?.let { FailedRow(it.reason) { context.onRetry(message) } }
        }
    }
}

/** What sits above the text: the sender in groups, the SMS tag, the reply quote, pictures. */
@Composable
private fun ColumnScope.BubbleTop(
    item: ChatItem.Bubble,
    context: RowContext,
    sender: String?,
) {
    val message = item.message
    val groupStart = context.kind == ChatKind.GROUP && item.firstInGroup
    if (!message.isOutgoing && groupStart && sender != null) SenderName(sender)
    val fellBack = message.transport == Transport.SMS && context.network == NetworkId.GMESSAGES
    if (message.isOutgoing && fellBack) SmsTag()
    message.quote?.let { QuoteBlock(it.senderName, it.text) { context.onQuote(message) } }
    message.attachments.forEach {
        AttachmentView(
            it,
            context.onNeed,
            context.uploads[message.id],
            context.player,
            context.gifsAutoplay,
            context.transcripts,
        )
    }
}

/** Whether a message's time shows, per Appearance > Timestamps (UI_DESIGN.md 4.3). */
fun showsTime(
    mode: TimestampMode,
    item: ChatItem.Bubble,
    revealed: Boolean,
) = when (mode) {
    TimestampMode.ALWAYS -> true
    TimestampMode.GROUPED -> item.lastInGroup
    TimestampMode.ON_TAP -> revealed
}

@Composable
private fun bodyText(message: Message): androidx.compose.ui.text.AnnotatedString? =
    when {
        message.deletedForEveryone || message.kind == MessageKind.DELETED -> null
        else -> message.body?.let { linked(it, message.linkPreview) }
    }

@Composable
private fun Footer(message: Message) {
    val deleted = message.deletedForEveryone || message.kind == MessageKind.DELETED
    Row(
        Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (deleted) {
            Text(
                stringResource(R.string.kind_deleted),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
            )
        }
        if (message.editedAt !=
            null
        ) {
            Text(stringResource(R.string.chat_edited), style = MaterialTheme.typography.labelSmall)
        }
        val waiting = message.status as? MessageStatus.Scheduled
        Text(
            waiting?.let { scheduledLabel(it.at) } ?: clockTime(message),
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current.copy(alpha = FADED),
        )
        if (message.isOutgoing) StatusMark(message.status)
    }
}

/** For day separators: "Today", "Yesterday", a weekday within the week, or the date. */
@Composable
fun dayLabel(
    day: java.time.LocalDate,
    today: java.time.LocalDate = java.time.LocalDate.now(),
): String {
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    return when {
        day == today -> stringResource(R.string.chat_today)
        day == today.minusDays(1) -> stringResource(R.string.chat_yesterday)
        day.isAfter(today.minusDays(WEEK)) -> DateTimeFormatter.ofPattern("EEEE", locale).format(day)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
    }
}

private val AVATAR = 32.dp
private const val QUOTE_TINT = 0.12f
private const val FADED = 0.7f
private const val WEEK = 7L
