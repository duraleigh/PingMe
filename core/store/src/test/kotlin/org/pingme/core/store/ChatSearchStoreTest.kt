// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.ChatId
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Search in chat's media chips, link chip, and date jump (UI_DESIGN.md 10.14). */
class ChatSearchStoreTest : StoreTest() {
    private val search by lazy { ChatSearchRepository(db) }
    private val c1 = ChatId("c1")

    private suspend fun seed() {
        accounts.upsert(account("a"))
        chats.upsert(chat("c1", "a"))
        chats.upsert(chat("c2", "a"))
        messages.upsert(message("text", "c1", body = "plain", sentAt = now - 3.days))
        messages.upsert(message("photo", "c1", body = null, sentAt = now - 2.days).copy(kind = MessageKind.IMAGE))
        messages.upsert(message("gif", "c1", body = null, sentAt = now - 1.days).copy(kind = MessageKind.GIF))
        messages.upsert(message("link", "c1", body = "see https://example.org/a", sentAt = now - 5.minutes))
        messages.upsert(message("elsewhere", "c2", body = null).copy(kind = MessageKind.IMAGE))
    }

    @Test
    fun mediaChipsFindTheirKindsInThisChatOnly() =
        runTest {
            seed()
            val found = search.ofKinds(c1, setOf(MessageKind.IMAGE, MessageKind.GIF)).first().map { it.id.value }
            assertEquals(listOf("gif", "photo"), found)
        }

    @Test
    fun theLinksChipFindsMessagesWithLinks() =
        runTest {
            seed()
            assertEquals(listOf("link"), search.withLinks(c1).first().map { it.id.value })
        }

    @Test
    fun aDateJumpLandsOnTheFirstMessageThatDay() =
        runTest {
            seed()
            assertEquals(MessageId("photo"), search.firstFrom(c1, now - 2.days - 1.minutes))
            assertEquals("the GIF and the link", 2, search.countNewer(messages.get(MessageId("photo"))!!))
        }
}
