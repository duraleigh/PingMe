// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.core.net.toUri
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

// The pieces inside and under a bubble.

@Composable
internal fun ColumnScope.SenderName(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/** "This one fell back to SMS" (UI_DESIGN.md 10.1). */
@Composable
internal fun SmsTag() {
    Text(
        "SMS",
        Modifier
            .padding(bottom = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The quoted message a reply answers; tapping it jumps there (UI_DESIGN.md 5.2). */
@Composable
internal fun QuoteBlock(
    name: String,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = LocalContentColor.current.copy(alpha = QUOTE_TINT),
        contentColor = LocalContentColor.current,
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (name.isNotEmpty()) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A link's title and description, from the network or fetched on the phone (UI_DESIGN.md 10.12). */
@Composable
internal fun LinkCard(preview: org.pingme.core.model.LinkPreview) {
    // Opens the cleaned link (UI_DESIGN.md 10.11) in the phone's browser.
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Surface(
        onClick = { runCatching { uri.openUri(preview.cleanedUrl) } },
        shape = RoundedCornerShape(10.dp),
        color = LocalContentColor.current.copy(alpha = QUOTE_TINT),
        contentColor = LocalContentColor.current,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            preview.imagePath?.let {
                AsyncImage(
                    model = File(it),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = LINK_IMAGE).clip(RoundedCornerShape(8.dp)),
                )
            }
            preview.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            preview.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                preview.cleanedUrl
                    .toUri()
                    .host
                    .orEmpty(),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Sending, sent, delivered, read, failed, scheduled (UI_DESIGN.md 3.2). Read is the two ticks
 * inside a filled circle, so it never looks like delivered whatever the bubble colour
 * (owner, Gate G2: a colour change alone was invisible on the bubble).
 */
@Composable
internal fun StatusMark(status: MessageStatus) {
    val (icon, label) =
        when (status) {
            MessageStatus.Sending -> UiR.drawable.ic_schedule to R.string.status_sending
            MessageStatus.Sent -> UiR.drawable.ic_check to R.string.status_sent
            MessageStatus.Delivered -> UiR.drawable.ic_done_all to R.string.status_delivered
            MessageStatus.Read -> UiR.drawable.ic_done_all to R.string.status_read
            is MessageStatus.Failed -> UiR.drawable.ic_error to R.string.status_failed
            is MessageStatus.Scheduled -> UiR.drawable.ic_schedule_send to R.string.status_scheduled
        }
    val description = stringResource(label)
    val described = Modifier.semantics { contentDescription = description }
    when (status) {
        MessageStatus.Read -> {
            // Filled circle in the text colour, ticks cut out of it in the bubble's colour.
            val ink = LocalContentColor.current
            Box(described.size(MARK_BADGE).background(ink, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(MARK_INNER), tint = contentColorFor(ink))
            }
        }

        is MessageStatus.Failed -> {
            Icon(painterResource(icon), null, described.size(MARK), tint = MaterialTheme.colorScheme.error)
        }

        else -> {
            Icon(
                painterResource(icon),
                null,
                described.size(MARK),
                tint = LocalContentColor.current.copy(alpha = FADED),
            )
        }
    }
}

private val MARK = 14.dp
private val MARK_BADGE = 16.dp
private val MARK_INNER = 11.dp

/** Reaction chips under the bubble: each emoji once, with how many (UI_DESIGN.md 3.2). */
@Composable
internal fun Reactions(message: Message) {
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        message.reactions.groupBy { it.emoji }.forEach { (emoji, who) ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 1.dp,
            ) {
                Text(
                    if (who.size > 1) "$emoji ${who.size}" else emoji,
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** "Not sent: <reason>" with Retry (UI_DESIGN.md 3.2). */
@Composable
internal fun FailedRow(
    reason: String,
    onRetry: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.chat_not_sent, reason),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_retry)) }
    }
}

/** "Sends Fri, Oct 2, 6:00 PM" under a scheduled message (UI_DESIGN.md 10.13). */
@Composable
internal fun scheduledLabel(at: kotlin.time.Instant): String =
    stringResource(
        R.string.later_sends_at,
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(at.toJavaInstant().atZone(ZoneId.systemDefault())),
    )

internal fun clockTime(message: Message): String =
    DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .format(message.sentAt.toJavaInstant().atZone(ZoneId.systemDefault()))

private val LINK_IMAGE = 140.dp
private const val QUOTE_TINT = 0.12f
private const val FADED = 0.7f
