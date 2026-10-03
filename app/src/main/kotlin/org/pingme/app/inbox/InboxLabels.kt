// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.pingme.app.R
import org.pingme.core.model.MessageKind
import org.pingme.core.model.NetworkId
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** A network's name as people know it. Brand names are not translated. */
val NetworkId.displayName: String
    get() =
        when (this) {
            NetworkId.GMESSAGES -> "Google Messages"
            NetworkId.SMS -> "SMS"
            NetworkId.WHATSAPP -> "WhatsApp"
            NetworkId.TELEGRAM -> "Telegram"
            NetworkId.SIGNAL -> "Signal"
            NetworkId.GVOICE -> "Google Voice"
            NetworkId.INSTAGRAM -> "Instagram"
            NetworkId.MESSENGER -> "Messenger"
            NetworkId.FBPAGE -> "Facebook Page"
            NetworkId.DEMO -> "Demo"
        }

/** The short badge on an inbox row and in the chat header (UI_DESIGN.md 10.1). */
fun badgeLabel(network: NetworkId): String =
    when (network) {
        // Rows and the chat header show no badge for Google Messages; where the network itself
        // is named (the account picker, the bottom bar) it is "GM" (owner, Gate G3).
        NetworkId.GMESSAGES -> {
            "GM"
        }

        NetworkId.SMS -> {
            "SMS"
        }

        NetworkId.WHATSAPP -> {
            "WA"
        }

        NetworkId.TELEGRAM -> {
            "TG"
        }

        NetworkId.SIGNAL -> {
            "Signal"
        }

        NetworkId.GVOICE -> {
            "Voice"
        }

        NetworkId.INSTAGRAM -> {
            "IG"
        }

        NetworkId.MESSENGER -> {
            "FB"
        }

        NetworkId.FBPAGE -> {
            "Page"
        }

        NetworkId.DEMO -> {
            "Demo"
        }
    }

/** "now", a time today, a weekday this week, or a date (UI_DESIGN.md 3.1). */
fun timeLabel(
    at: Instant,
    now: Instant,
    nowLabel: String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (now - at < 1.minutes) return nowLabel
    val then = at.toJavaInstant().atZone(zone)
    val today = now.toJavaInstant().atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), today)
    val pattern =
        when {
            days == 0L -> return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(then)
            days < WEEK -> "EEE"
            then.year == today.year -> DateFormat.getBestDateTimePattern(locale, "MMMd")
            else -> return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(then)
        }
    return DateTimeFormatter.ofPattern(pattern, locale).format(then)
}

private const val WEEK = 7

/** What a row previews: the text, or what kind of message it was, with who sent it in groups. */
@Composable
fun previewText(row: ChatRow): String {
    val last = row.last ?: return stringResource(R.string.inbox_no_messages)
    if (row.chat.isObscured) return stringResource(R.string.inbox_hidden_preview)
    val body = kindText(last.kind, last.body).replace('\n', ' ')
    return when {
        last.isOutgoing -> {
            stringResource(R.string.inbox_you, body)
        }

        row.chat.kind == org.pingme.core.model.ChatKind.GROUP && last.senderName != null -> {
            stringResource(R.string.inbox_sender, last.senderName!!.substringBefore(' '), body)
        }

        else -> {
            body
        }
    }
}

/** The text of a message, or what kind of message it was when it has no text to show. */
@Composable
private fun kindText(
    kind: MessageKind,
    body: String?,
): String =
    when (kind) {
        MessageKind.TEXT -> body.orEmpty()
        MessageKind.VOICE -> stringResource(R.string.kind_voice)
        MessageKind.GIF -> stringResource(R.string.kind_gif)
        MessageKind.IMAGE -> body?.takeIf { it.isNotBlank() } ?: stringResource(R.string.kind_image)
        MessageKind.VIDEO -> body?.takeIf { it.isNotBlank() } ?: stringResource(R.string.kind_video)
        MessageKind.FILE -> stringResource(R.string.kind_file)
        MessageKind.LOCATION -> stringResource(R.string.kind_location)
        MessageKind.CONTACT -> stringResource(R.string.kind_contact)
        MessageKind.STICKER -> stringResource(R.string.kind_sticker)
        MessageKind.DELETED -> stringResource(R.string.kind_deleted)
    }
