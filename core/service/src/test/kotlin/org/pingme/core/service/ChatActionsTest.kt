// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Reaction

class ChatActionsTest : ServiceTest() {
    private val connector = FakeConnector()
    private val actions by lazy {
        ChatActions(
            chats,
            messages,
            accounts,
            ConnectorRegistry(
                mapOf(NetworkId.DEMO to connector),
            ),
            applier,
        )
    }

    private suspend fun seed(count: Int = 1): List<ChatId> {
        accounts.upsert(account())
        val snapshots = (1..count).map { chatSnapshot(remote = "c$it", unread = 2) }
        applier.applyChats(snapshots)
        return snapshots.map { it.id }
    }

    private suspend fun chat(id: ChatId) = chats.get(id)!!

    @Test
    fun pinsKeepTheirOrderAndStopAtTwelve() =
        runTest {
            val ids = seed(13)
            ids.take(12).forEach { assertTrue(actions.setPinned(it, true)) }
            assertFalse("the 13th pin is refused", actions.setPinned(ids[12], true))
            assertFalse(chat(ids[12]).isPinned)
            assertEquals((0..11).toList(), ids.take(12).map { chat(it).pinOrder })

            actions.movePin(ids[11], 0)
            assertEquals(
                ids[11],
                chats
                    .all()
                    .first()
                    .filter { it.isPinned }
                    .minBy { it.pinOrder!! }
                    .id,
            )

            actions.setPinned(ids[0], false)
            assertNull(chat(ids[0]).pinOrder)
            assertTrue("a freed slot can be used", actions.setPinned(ids[12], true))
        }

    @Test
    fun markingReadClearsTheBadgeAndTellsTheNetwork() =
        runTest {
            val id = seed().single()
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1")))
            actions.setRead(id, true)
            assertEquals(0, chat(id).unreadCount)
            assertEquals(listOf(id to accountId.message("m1")), connector.readMarkers.toList())

            actions.setRead(id, false)
            assertEquals(1, chat(id).unreadCount)
            assertEquals("unread is local only", 1, connector.readMarkers.size)
        }

    @Test
    fun archiveAndLowPriorityTakeAChatOutOfThePinnedGrid() =
        runTest {
            val (a, b) = seed(2)
            actions.setPinned(a, true)
            actions.setPinned(b, true)
            actions.setArchived(a, true)
            actions.setLowPriority(b, true)
            assertTrue(chat(a).isArchived && !chat(a).isPinned)
            assertTrue(chat(b).isLowPriority && !chat(b).isPinned)
            assertTrue(chats.inbox().first().isEmpty())

            actions.setPinned(a, true)
            assertFalse("pinning brings an archived chat back", chat(a).isArchived)
        }

    @Test
    fun muteObscureAndDelete() =
        runTest {
            val id = seed().single()
            actions.setMuted(id, true)
            actions.setObscured(id, true)
            assertTrue(chat(id).isMuted && chat(id).isObscured)
            assertEquals("a muted chat stops counting", 0, chats.unreadTotals().first().total)
            actions.delete(id)
            assertNull(chats.get(id))
        }

    @Test
    fun onlyOtherPeoplesReactionsAreAnnounced() =
        runTest {
            val id = seed().single()
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1", outgoing = true)))
            val heard = mutableListOf<IncomingReaction>()
            val listening =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    reactionFeed.reactions.collect {
                        heard +=
                            it
                    }
                }
            applier.apply(
                ConnectorEvent.ReactionChanged(
                    accountId,
                    accountId.message("m1"),
                    Reaction("😂", sam().id, now),
                    removed = false,
                ),
            )
            applier.apply(
                ConnectorEvent.ReactionChanged(
                    accountId,
                    accountId.message("m1"),
                    Reaction("👍", accountId.person("me"), now),
                    removed = false,
                ),
            )
            eventually { heard.isNotEmpty() }
            listening.cancel()
            assertEquals(listOf(IncomingReaction(id, "😂", accountId.message("m1"))), heard)
        }
}
