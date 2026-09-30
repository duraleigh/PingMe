// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import org.pingme.core.model.Message
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaInstant

/** One line of the chat list, newest first (the list is drawn bottom-up). */
sealed interface ChatItem {
    val key: String

    /**
     * A message. [firstInGroup] and [lastInGroup] follow reading order (top to bottom):
     * consecutive messages from the same sender close in time form a group, the sender's
     * name shows on the first, and only the last carries the tail (UI_DESIGN.md 3.2).
     */
    data class Bubble(
        val message: Message,
        val firstInGroup: Boolean,
        val lastInGroup: Boolean,
    ) : ChatItem {
        override val key get() = message.id.value
    }

    /** "Today", "Yesterday", or the date, above the first message of each day. */
    data class Day(
        val date: LocalDate,
    ) : ChatItem {
        override val key get() = "day-$date"
    }

    /** Above the first message that was unread when the chat opened. */
    data object NewMessages : ChatItem {
        override val key get() = "new-messages"
    }
}

/**
 * Builds the list from messages newest first. [firstUnreadId] is the oldest message that
 * was unread when the chat opened, if any.
 */
fun chatItems(
    newestFirst: List<Message>,
    firstUnreadId: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    newestFirst.forEachIndexed { i, message ->
        val newer = newestFirst.getOrNull(i - 1)
        val older = newestFirst.getOrNull(i + 1)
        val day = message.day(zone)
        items +=
            ChatItem.Bubble(
                message = message,
                firstInGroup = older == null || !together(older, message, zone),
                lastInGroup = newer == null || !together(message, newer, zone),
            )
        if (message.id.value == firstUnreadId) items += ChatItem.NewMessages
        if (older == null || older.day(zone) != day) items += ChatItem.Day(day)
    }
    return items
}

/** Same sender, same day, and within a few minutes: drawn as one group. */
private fun together(
    earlier: Message,
    later: Message,
    zone: ZoneId,
) = earlier.senderId == later.senderId &&
    earlier.isOutgoing == later.isOutgoing &&
    later.sentAt - earlier.sentAt < GROUP_GAP &&
    earlier.day(zone) == later.day(zone)

private fun Message.day(zone: ZoneId) = sentAt.toJavaInstant().atZone(zone).toLocalDate()

private val GROUP_GAP = 5.minutes

/** The oldest incoming message among the newest [unread] incoming ones: where "New messages" goes. */
fun firstUnread(
    newestFirst: List<Message>,
    unread: Int,
): String? {
    if (unread <= 0) return null
    return newestFirst
        .filter { !it.isOutgoing }
        .take(unread)
        .lastOrNull()
        ?.id
        ?.value
}
