// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.attachment
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
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
            // A listing claiming newer activity still cannot: PingMe counts its own messages.
            applier.apply(
                ConnectorEvent.ChatUpdated(accountId, chatSnapshot(unread = 1).copy(lastActivityAt = now + 1.minutes)),
            )
            assertEquals(0, chats.get(chatId)?.unreadCount)
            // The newer message itself is news, and stays counted through the next listing.
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m2", sentAt = now + 1.minutes)))
            assertEquals(1, chats.get(chatId)?.unreadCount)
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot(unread = 5)))
            assertEquals(1, chats.get(chatId)?.unreadCount)
        }

    @Test
    fun aChatWhoseNewestMessageIsOursIsReadWhateverTheNetworkSays() =
        runTest {
            seed()
            messages.upsert(messageSnapshot("mine", outgoing = true, sentAt = now + 1.minutes).message)
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot(unread = 3)))
            assertEquals(0, chats.get(chatId)?.unreadCount)
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
    fun aReactionToAMessageTheStoreNeverGotIsIgnored() =
        runTest {
            seed()
            val reaction = Reaction("❤️", sam().id, now)
            applier.apply(ConnectorEvent.ReactionChanged(accountId, accountId.message("never-stored"), reaction, false))
            assertNull(messages.get(accountId.message("never-stored")))
        }

    @Test
    fun stalePeopleKnownOnlyByAHiddenIdGoWhenThePeopleListComes() =
        runTest {
            seed()
            val hidden = sam().copy(id = accountId.person("128226349293594@lid"), networkHandle = "128226349293594@lid")
            contacts.upsert(hidden)
            applier.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(sam())))
            assertNull(contacts.person(hidden.id))
            assertEquals("Sam Ortiz", contacts.person(sam().id)?.displayName)
        }

    @Test
    fun aPeopleListNamesOneToOneChatsStillTitledByTheirNumber() =
        runTest {
            // WhatsApp never lists one-to-one chats again, so a chat stored before the names
            // came kept its number for good (owner, Gate G7, round 2).
            seed()
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot("c1", title = "+15555550123")))
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot("named", title = "Sam (work)")))
            applier.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(sam())))
            assertEquals("Sam Ortiz", chats.get(accountId.chat("c1"))?.title)
            assertEquals("the chat already had a name", "Sam (work)", chats.get(accountId.chat("named"))?.title)
        }

    @Test
    fun aChatMadeFromYourOwnMessageIsNamedByThePeopleListToo() =
        runTest {
            // Such a chat lists nobody; the person's address is the chat's (owner, Gate G7, round 3).
            accounts.upsert(account())
            applier.apply(
                ConnectorEvent.NewMessage(accountId, messageSnapshot("o", chatRemote = "sam", outgoing = true)),
            )
            chats.update(accountId.chat("sam")) { it.copy(title = "+15555550123") }
            applier.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(sam().copy(phoneNumber = "+15555550123"))))
            val chat = chats.get(accountId.chat("sam"))!!
            assertEquals("Sam Ortiz", chat.title)
            assertEquals(listOf(sam().id), chat.participants)
            // A chat titled by the number alone, under some other id, is found by the number.
            applier.apply(
                ConnectorEvent.ChatUpdated(
                    accountId,
                    chatSnapshot("odd", title = "15555550123").copy(participants = emptyList()),
                ),
            )
            applier.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(sam().copy(phoneNumber = "+15555550123"))))
            assertEquals("Sam Ortiz", chats.get(accountId.chat("odd"))?.title)
        }

    @Test
    fun aPeopleListWithoutANameLeavesTheNumberAlone() =
        runTest {
            seed()
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot("c1", title = "+15555550123")))
            applier.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(sam().copy(displayName = "+15555550123"))))
            assertEquals("+15555550123", chats.get(accountId.chat("c1"))?.title)
        }

    @Test
    fun theNetworksNobodyPlaceholderChatGoesWithThePeopleList() =
        runTest {
            seed()
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot("0@s.whatsapp.net", title = "+0")))
            applier.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(sam())))
            assertNull(chats.get(accountId.chat("0@s.whatsapp.net")))
            // And a later listing of it is ignored.
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot("0@s.whatsapp.net", title = "+0")))
            assertNull(chats.get(accountId.chat("0@s.whatsapp.net")))
        }

    @Test
    fun yourOwnMessageForAnUnknownChatNeverNamesTheChatYou() =
        runTest {
            accounts.upsert(account())
            applier.apply(
                ConnectorEvent.NewMessage(accountId, messageSnapshot("o", chatRemote = "fresh", outgoing = true)),
            )
            val chat = chats.get(accountId.chat("fresh"))!!
            assertEquals("", chat.title)
            assertEquals(0, chat.unreadCount)
            assertEquals("hi", messages.get(accountId.message("o"))?.body)
        }

    @Test
    fun theNetworksCopyOfASentPictureKeepsThePhonesFile() =
        runTest {
            seed()
            val file =
                Attachment(
                    accountId.attachment("tmp/p-0"),
                    AttachmentKind.IMAGE,
                    "image/jpeg",
                    "p.jpg",
                    3,
                    "/data/p.jpg",
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                )
            val standIn =
                messageSnapshot("tmp/p", body = "", outgoing = true).let {
                    it.copy(message = it.message.copy(networkRemoteId = "tmp/p", attachments = listOf(file)))
                }
            applier.apply(ConnectorEvent.NewMessage(accountId, standIn))
            val remote = file.copy(id = accountId.attachment("real/0"), localPath = null, remoteRef = "cdn/p")
            val copy =
                messageSnapshot("real", body = "", outgoing = true).let {
                    it.copy(message = it.message.copy(attachments = listOf(remote)))
                }
            applier.apply(ConnectorEvent.NewMessage(accountId, copy))
            assertNull(messages.get(accountId.message("tmp/p")))
            assertEquals(
                "/data/p.jpg",
                messages
                    .get(accountId.message("real"))
                    ?.attachments
                    ?.single()
                    ?.localPath,
            )
        }

    @Test
    fun twoChatsThatWereOneFoldTogether() =
        runTest {
            accounts.upsert(account())
            applier.apply(
                ConnectorEvent.NewMessage(
                    accountId,
                    messageSnapshot("h1", chatRemote = "555@lid", body = "under the hidden id"),
                ),
            )
            applier.apply(
                ConnectorEvent.NewMessage(
                    accountId,
                    messageSnapshot("p1", chatRemote = "15555550123@s.whatsapp.net", body = "under the number"),
                ),
            )
            val hidden = accountId.chat("555@lid")
            val number = accountId.chat("15555550123@s.whatsapp.net")
            applier.apply(ConnectorEvent.ChatMerged(accountId, hidden, number))
            assertNull(chats.get(hidden))
            assertEquals(number, messages.get(accountId.message("h1"))?.chatId)
            assertEquals(number, messages.get(accountId.message("p1"))?.chatId)
            assertEquals(2, chats.get(number)?.unreadCount)
            // A merge into a chat not stored yet makes it from the old one.
            applier.apply(
                ConnectorEvent.NewMessage(accountId, messageSnapshot("h2", chatRemote = "777@lid", body = "x")),
            )
            applier.apply(
                ConnectorEvent.ChatMerged(
                    accountId,
                    accountId.chat("777@lid"),
                    accountId.chat("17770000000@s.whatsapp.net"),
                ),
            )
            assertEquals(accountId.chat("17770000000@s.whatsapp.net"), messages.get(accountId.message("h2"))?.chatId)
            assertNull(chats.get(accountId.chat("777@lid")))
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
