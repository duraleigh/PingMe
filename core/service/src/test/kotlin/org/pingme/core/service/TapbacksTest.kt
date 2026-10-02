// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.MessageKind
import kotlin.time.Duration.Companion.minutes

/** iPhone reactions sent as SMS text land on the message they mean (owner, Gate G3). */
class TapbacksTest : ServiceTest() {
    private val tapbacks by lazy { Tapbacks(messages) }

    private suspend fun seed() {
        accounts.upsert(account())
        applier.applyChats(listOf(chatSnapshot()))
        applier.apply(
            ConnectorEvent.NewMessage(accountId, messageSnapshot("t1", body = "See you at 7", outgoing = true)),
        )
        val picture =
            messageSnapshot("p1", body = "", sentAt = now + 1.minutes).let {
                it.copy(message = it.message.copy(kind = MessageKind.IMAGE))
            }
        applier.apply(ConnectorEvent.NewMessage(accountId, picture))
        applier.apply(
            ConnectorEvent.NewMessage(
                accountId,
                messageSnapshot(
                    "t2",
                    body = "Great",
                    sentAt =
                        now + 2.minutes,
                ),
            ),
        )
    }

    @Test
    fun theWordingIsRead() {
        assertEquals("\u2764\uFE0F", Tapbacks.parse("Loved an image")!!.emoji)
        assertEquals("See you at 7", Tapbacks.parse("Laughed at “See you at 7”")!!.quoted)
        assertEquals(true, Tapbacks.parse("Removed a like from “ok”")!!.removed)
        assertNull(Tapbacks.parse("Loved every minute of it"))
        assertNull(Tapbacks.parse("Liked"))
    }

    @Test
    fun aQuotedTextReactsToThatMessageAndAnImageToTheNewestPicture() =
        runBlocking {
            seed()
            val onText =
                tapbacks.asReaction(
                    messageSnapshot(
                        "r1",
                        body = "Loved “See you at 7”",
                        sentAt =
                            now + 3.minutes,
                    ).message,
                )!!
            assertEquals(accountId.message("t1"), onText.messageId)
            assertEquals("\u2764\uFE0F", onText.reaction.emoji)
            assertEquals(false, onText.removed)
            val onPicture =
                tapbacks.asReaction(
                    messageSnapshot(
                        "r2",
                        body = "Laughed at an image",
                        sentAt =
                            now + 3.minutes,
                    ).message,
                )!!
            assertEquals(accountId.message("p1"), onPicture.messageId)
            val gone =
                tapbacks.asReaction(
                    messageSnapshot(
                        "r3",
                        body = "Removed a heart from “Great”",
                        sentAt =
                            now + 4.minutes,
                    ).message,
                )!!
            assertEquals(accountId.message("t2"), gone.messageId)
            assertEquals(true, gone.removed)
        }

    @Test
    fun plainTextAndUnmatchedReactionsStayMessages() =
        runBlocking {
            seed()
            val plain =
                ConnectorEvent.NewMessage(
                    accountId,
                    messageSnapshot(
                        "r1",
                        body = "Loved it!",
                        sentAt =
                            now + 3.minutes,
                    ),
                )
            assertTrue(tapbacks.rewrite(plain) === plain)
            val nothing =
                ConnectorEvent.NewMessage(
                    accountId,
                    messageSnapshot(
                        "r2",
                        body = "Loved a video",
                        sentAt =
                            now + 3.minutes,
                    ),
                )
            assertTrue(tapbacks.rewrite(nothing) === nothing)
        }

    @Test
    fun historyAppliesReactionsAfterTheMessagesTheyRefer() =
        runBlocking {
            accounts.upsert(account())
            applier.applyChats(listOf(chatSnapshot()))
            val batch =
                ConnectorEvent.HistoryBatch(
                    accountId,
                    accountId.chat("c1"),
                    listOf(
                        messageSnapshot("r1", body = "Liked “Great”", sentAt = now + 2.minutes),
                        messageSnapshot("t2", body = "Great", sentAt = now + 1.minutes),
                    ),
                    complete = true,
                )
            applier.apply(batch)
            assertNull(messages.get(accountId.message("r1")))
            assertEquals(listOf("\uD83D\uDC4D"), messages.get(accountId.message("t2"))!!.reactions.map { it.emoji })
        }
}
