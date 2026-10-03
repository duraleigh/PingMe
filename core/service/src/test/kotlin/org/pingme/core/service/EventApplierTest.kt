// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.Reaction
import kotlin.time.Duration.Companion.minutes

class EventApplierTest : ServiceTest() {
    private val chatId get() = accountId.chat("c1")

    private suspend fun seed() {
        accounts.upsert(account())
        applier.applyChats(listOf(chatSnapshot(unread = 1)))
    }

    @Test
    fun aNetworkSnapshotNeverUndoesWhatTheUserChose() =
        runTest {
            seed()
            chats.update(chatId) {
                it.copy(isPinned = true, pinOrder = 0, isMuted = true, isObscured = true, nameOverride = "Sam (work)")
            }
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot(unread = 7, title = "Sam O.")))
            val chat = chats.get(chatId)!!
            assertEquals("Sam O.", chat.title)
            assertEquals(7, chat.unreadCount)
            assertEquals(true, chat.isPinned)
            assertEquals(true, chat.isMuted)
            assertEquals(true, chat.isObscured)
            assertEquals("Sam (work)", chat.nameOverride)
            assertEquals("Sam Ortiz", contacts.person(sam().id)?.displayName)
        }

    @Test
    fun anIncomingMessageCountsAsUnreadAndBringsAnArchivedChatBack() =
        runTest {
            seed()
            chats.update(chatId) { it.copy(isArchived = true) }
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1", sentAt = now + 1.minutes)))
            val chat = chats.get(chatId)!!
            assertEquals(2, chat.unreadCount)
            assertEquals(now + 1.minutes, chat.lastActivityAt)
            assertEquals(false, chat.isArchived)
            assertEquals("hi", messages.get(accountId.message("m1"))?.body)
        }

    @Test
    fun anOutgoingMessageMeansTheChatIsRead() =
        runTest {
            seed()
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("o1", outgoing = true)))
            assertEquals(0, chats.get(chatId)?.unreadCount)
        }

    @Test
    fun aChatReadHereStaysReadWhenTheNetworkListsItUnreadAgain() =
        runTest {
            seed()
            chats.update(chatId) { it.copy(unreadCount = 0, readUpTo = now) }
            // The network's own read mark did not take: it still counts the old message as unread.
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot(unread = 1)))
            assertEquals(0, chats.get(chatId)?.unreadCount)
            // Something newer than the read mark is news again.
            applier.apply(
                ConnectorEvent.ChatUpdated(accountId, chatSnapshot(unread = 1).copy(lastActivityAt = now + 1.minutes)),
            )
            assertEquals(1, chats.get(chatId)?.unreadCount)
        }

    @Test
    fun anOldMessageHandedBackAfterReopeningDoesNotCountAsUnreadAgain() =
        runTest {
            seed()
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1", sentAt = now + 1.minutes)))
            chats.update(chatId) { it.copy(unreadCount = 0, readUpTo = now + 1.minutes) }
            // The bridge forgot what it had shown and reports the same message as new.
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1", sentAt = now + 1.minutes)))
            assertEquals(0, chats.get(chatId)?.unreadCount)
            // A message from before the read mark that the store never saw is not news either.
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m0", sentAt = now)))
            assertEquals(0, chats.get(chatId)?.unreadCount)
            // A genuinely new one is.
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m2", sentAt = now + 2.minutes)))
            assertEquals(1, chats.get(chatId)?.unreadCount)
        }

    @Test
    fun aChatDeletedHereStaysGoneUntilSomethingNewerComes() =
        runTest {
            seed()
            chats.hide(chatId, now)
            chats.delete(chatId)
            applier.applyChats(listOf(chatSnapshot(unread = 1)))
            val history =
                ConnectorEvent.HistoryBatch(
                    accountId,
                    chatId,
                    listOf(messageSnapshot("old")),
                    complete = true,
                )
            applier.apply(history)
            assertNull(chats.get(chatId))
            assertNull(messages.get(accountId.message("old")))
            // A message after the deletion brings the chat back, as new.
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("fresh", sentAt = now + 1.minutes)))
            assertEquals(1, chats.get(chatId)?.unreadCount)
            assertEquals("hi", messages.get(accountId.message("fresh"))?.body)
            applier.applyChats(listOf(chatSnapshot(unread = 3).copy(lastActivityAt = now + 1.minutes)))
            assertEquals(3, chats.get(chatId)?.unreadCount)
        }

    @Test
    fun aMessageForAnUnknownChatStillLands() =
        runTest {
            accounts.upsert(account())
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m", chatRemote = "new-chat")))
            val chat = chats.get(accountId.chat("new-chat"))!!
            assertEquals("Sam Ortiz", chat.title)
            assertEquals(1, chat.unreadCount)
        }

    @Test
    fun updatesRemovalsReactionsReceiptsAndState() =
        runTest {
            seed()
            applier.apply(
                ConnectorEvent.NewMessage(
                    accountId,
                    messageSnapshot(
                        "o1",
                        outgoing = true,
                        sentAt =
                            now - 1.minutes,
                    ),
                ),
            )
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m2")))
            val o1 = accountId.message("o1")

            applier.apply(ConnectorEvent.ReadReceipt(accountId, chatId, o1, reader = null))
            assertEquals(MessageStatus.Read, messages.get(o1)?.status)

            val heart = Reaction("❤️", sam().id, now)
            applier.apply(ConnectorEvent.ReactionChanged(accountId, o1, heart, removed = false))
            assertEquals(listOf(heart), messages.get(o1)?.reactions)
            applier.apply(ConnectorEvent.ReactionChanged(accountId, o1, heart, removed = true))
            assertEquals(emptyList<Reaction>(), messages.get(o1)?.reactions)

            applier.apply(ConnectorEvent.MessageUpdated(accountId, messageSnapshot("m2", body = "edited")))
            assertEquals("edited", messages.get(accountId.message("m2"))?.body)
            applier.apply(ConnectorEvent.MessageRemoved(accountId, chatId, accountId.message("m2")))
            assertNull(messages.get(accountId.message("m2")))

            applier.apply(ConnectorEvent.State(accountId, ConnectionState.ActionNeeded("Re-pair", null)))
            assertEquals(ConnectionState.ActionNeeded("Re-pair", null), accounts.get(accountId)?.state)

            applier.apply(ConnectorEvent.ChatRemoved(accountId, chatId))
            assertNull(chats.get(chatId))
        }

    @Test
    fun theNetworksCopyOfASentMessageRetiresItsStandIn() =
        runTest {
            seed()
            val standIn =
                messageSnapshot("tmp/abc", body = "On my way", outgoing = true).let {
                    it.copy(message = it.message.copy(networkRemoteId = "tmp/abc"))
                }
            applier.apply(ConnectorEvent.MessageUpdated(accountId, standIn))
            applier.apply(
                ConnectorEvent.NewMessage(accountId, messageSnapshot("777", body = "On my way", outgoing = true)),
            )
            assertEquals(null, messages.get(accountId.message("tmp/abc")))
            assertEquals("On my way", messages.get(accountId.message("777"))!!.body)
            // A different text keeps its stand-in: that send is still waiting.
            applier.apply(ConnectorEvent.MessageUpdated(accountId, standIn))
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("778", body = "Other", outgoing = true)))
            assertEquals("On my way", messages.get(accountId.message("tmp/abc"))!!.body)
        }

    @Test
    fun historyBatchesAddMessagesWithoutCountingThemUnread() =
        runTest {
            seed()
            val batch =
                listOf(
                    messageSnapshot("h1", sentAt = now - 2.minutes),
                    messageSnapshot(
                        "h2",
                        sentAt =
                            now - 1.minutes,
                    ),
                )
            applier.apply(ConnectorEvent.HistoryBatch(accountId, chatId, batch, complete = true))
            assertEquals("hi", messages.get(accountId.message("h1"))?.body)
            assertEquals(1, chats.get(chatId)?.unreadCount)
        }

    @Test
    fun typingShowsUntilTheirMessageArrives() =
        runTest {
            seed()
            applier.apply(ConnectorEvent.Typing(accountId, chatId, sam().id, typing = true))
            assertEquals(mapOf(chatId to setOf(sam().id)), typing.typing.value)
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m")))
            assertEquals(emptyMap<Any, Any>(), typing.typing.value)
        }
}
