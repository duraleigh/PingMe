// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.chat
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Quote
import org.pingme.core.store.PinnedMessageRepository
import kotlin.time.Duration.Companion.minutes

class MessageActionsTest : ServiceTest() {
    private val connector = FakeConnector()
    private val actions by lazy {
        MessageActions(
            chats,
            messages,
            PinnedMessageRepository(db),
            accounts,
            ConnectorRegistry(
                mapOf(NetworkId.DEMO to connector),
            ),
            applier,
            clock,
        )
    }
    private val chatId get() = accountId.chat("c1")

    private suspend fun seed() {
        accounts.upsert(account())
        applier.applyChats(listOf(chatSnapshot(unread = 2)))
    }

    @Test
    fun aSentMessageReplacesItsPendingBubble() =
        runTest {
            seed()
            connector.sendResult =
                { draft -> SendResult.Sent(messageSnapshot("net-1", body = draft.body!!, outgoing = true)) }
            val sent = actions.send(chatId, "On my way")
            val stored = messages.latest(chatId, 10).first()
            assertEquals(listOf("net-1"), stored.map { it.id.value.substringAfter('/') })
            assertEquals("On my way", sent.body)
            assertEquals("sending clears the badge", 0, chats.get(chatId)!!.unreadCount)
        }

    @Test
    fun aFailedSendStaysWithItsReasonAndCanBeRetried() =
        runTest {
            seed()
            connector.sendResult = { SendResult.Failed("No signal", retryable = true) }
            val failed = actions.send(chatId, "Hello")
            assertEquals(MessageStatus.Failed("No signal"), failed.status)
            assertEquals(listOf(failed), messages.latest(chatId, 10).first())

            connector.sendResult = { SendResult.Sent(messageSnapshot("net-2", body = "Hello", outgoing = true)) }
            actions.retry(failed)
            assertEquals(listOf("net-2"), messages.latest(chatId, 10).first().map { it.id.value.substringAfter('/') })
        }

    @Test
    fun aReplyCarriesItsQuote() =
        runTest {
            seed()
            applier.apply(
                org.pingme.core.connector.ConnectorEvent.NewMessage(
                    accountId,
                    messageSnapshot("m1", body = "Coming tonight?"),
                ),
            )
            val original = messages.latest(chatId, 1).first().single()
            var sentQuote: Quote? = null
            connector.sendResult = { draft ->
                sentQuote = draft.quote
                SendResult.Failed("stop here", retryable = false)
            }
            actions.send(chatId, "Yes", replyTo = original, replyToName = "Sam")
            assertEquals(Quote("Sam", "Coming tonight?"), sentQuote)
        }

    @Test
    fun pinsAreLocalAndNewestFirst() =
        runTest {
            seed()
            applier.apply(
                org.pingme.core.connector.ConnectorEvent.NewMessage(
                    accountId,
                    messageSnapshot(
                        "a",
                        sentAt =
                            now - 5.minutes,
                    ),
                ),
            )
            applier.apply(
                org.pingme.core.connector.ConnectorEvent
                    .NewMessage(accountId, messageSnapshot("b")),
            )
            val (b, a) = messages.latest(chatId, 2).first()
            actions.pin(a)
            actions.pin(b)
            assertEquals(listOf(b.id, a.id), PinnedMessageRepository(db).pinned(chatId).first().map { it.id })
            actions.unpin(b.id)
            assertEquals(listOf(a.id), PinnedMessageRepository(db).pinned(chatId).first().map { it.id })
        }

    @Test
    fun olderHistoryLoadsUntilThereIsNoMore() =
        runTest {
            seed()
            connector.history = (1..3).map { messageSnapshot("h$it", sentAt = now - it.minutes) }
            assertFalse("fewer than a page means that was all", actions.loadOlder(chatId, count = 5))
            assertEquals(3, messages.latest(chatId, 10).first().size)
            assertTrue("older history is never unread", chats.get(chatId)!!.unreadCount == 2)
        }
}
