// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.PersonId
import org.pingme.core.model.Transport
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Grouping, day separators, and the new-messages line (UI_DESIGN.md 3.2). */
class ChatItemTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")

    private fun msg(
        id: String,
        at: Instant,
        from: String = "sam",
    ) = Message(
        MessageId(id),
        ChatId("c"),
        PersonId(from),
        at,
        at,
        id,
        MessageKind.TEXT,
        emptyList(),
        null,
        null,
        null,
        false,
        MessageStatus.Delivered,
        emptyList(),
        Transport.NETWORK,
        id,
        null,
        isOutgoing = from == "me",
    )

    private fun describe(items: List<ChatItem>) =
        items.map {
            when (it) {
                is ChatItem.Bubble -> {
                    it.message.id.value + (if (it.firstInGroup) "^" else "") +
                        (if (it.lastInGroup) "v" else "")
                }

                is ChatItem.Day -> {
                    "[${it.date}]"
                }

                ChatItem.NewMessages -> {
                    "[new]"
                }
            }
        }

    @Test
    fun groupsBySenderAndTimeWithDaysBetween() {
        // Newest first, as the store gives them.
        val messages =
            listOf(
                msg("e", now, "me"),
                msg("d", now - 1.minutes, "me"),
                msg("c", now - 2.minutes),
                msg("b", now - 4.minutes),
                msg("a", now - 25.hours),
            )
        assertEquals(
            listOf("e v", "d^", "c v", "b^", "[2026-09-30]", "a^v", "[2026-09-29]").map { it.replace(" ", "") },
            describe(chatItems(messages, firstUnreadId = null, zone = ZoneOffset.UTC)),
        )
    }

    @Test
    fun theNewMessagesLineSitsAboveTheOldestUnread() {
        val messages =
            listOf(
                msg("c", now),
                msg("b", now - 1.minutes),
                msg("mine", now - 2.minutes, "me"),
                msg(
                    "a",
                    now - 3.minutes,
                ),
            )
        val first = firstUnread(messages, unread = 2)
        assertEquals("b", first)
        val items = describe(chatItems(messages, first, ZoneOffset.UTC))
        assertEquals(items.indexOf("[new]"), items.indexOfFirst { it.startsWith("b") } + 1)
        assertEquals(null, firstUnread(messages, unread = 0))
        assertEquals(LocalDate.of(2026, 9, 30), (chatItems(messages, null, ZoneOffset.UTC).last() as ChatItem.Day).date)
    }

    @Test
    fun aSentMessageKeepsThePendingBubblesKey() {
        // The network's copy "net-1" replaced the pending bubble "pending-1": same key, one bubble.
        val alias = mapOf("net-1" to "pending-1")
        val shownAs = { m: Message -> alias[m.id.value] ?: m.id.value }
        val sent = listOf(msg("net-1", now, "me"), msg("a", now - 1.minutes))
        assertEquals(listOf("pending-1", "a"), chatItems(sent, null, ZoneOffset.UTC, shownAs).bubbleKeys())
        // While the pending bubble is still in the list, the copy keeps its own id: keys stay unique.
        val both = listOf(msg("net-1", now, "me"), msg("pending-1", now, "me"))
        assertEquals(listOf("net-1", "pending-1"), chatItems(both, null, ZoneOffset.UTC, shownAs).bubbleKeys())
    }

    private fun List<ChatItem>.bubbleKeys() = filterIsInstance<ChatItem.Bubble>().map { it.key }
}
