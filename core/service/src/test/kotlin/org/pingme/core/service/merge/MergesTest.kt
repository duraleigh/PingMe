// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.merge

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.chat
import org.pingme.core.connector.person
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.ContactId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.service.ChatActions
import org.pingme.core.service.FakeConnector
import org.pingme.core.service.ServiceTest
import org.pingme.core.store.MergeRepository
import kotlin.time.Duration.Companion.hours

/** Merged chats in the store and the service (UI_DESIGN.md 10.15; owner, Phase 7). */
class MergesTest : ServiceTest() {
    private val other = AccountId("wa")
    private val merged by lazy { MergeRepository(db) }
    private val merges by lazy { Merges(chats, merged, contacts) }
    private val connector = FakeConnector()
    private val actions by lazy {
        ChatActions(
            chats,
            messages,
            accounts,
            ConnectorRegistry(mapOf(NetworkId.DEMO to connector)),
            applier,
            settings,
            router,
            merged,
        )
    }

    private val c1 get() = accountId.chat("c1")
    private val w1 get() = other.chat("w1")

    private suspend fun seed() {
        accounts.upsert(account())
        accounts.upsert(
            Account(
                other,
                NetworkId.DEMO,
                "WhatsApp",
                0,
                ConnectionState.Connected,
                true,
                NotificationMode.NORMAL,
                "cred",
            ),
        )
        applier.applyChats(listOf(chatSnapshot("c1", unread = 1)))
        val samOnWa = sam().copy(id = other.person("sam"), accountId = other, displayName = "+15555550123")
        applier.applyChats(
            listOf(
                chatSnapshot("w1", unread = 2, title = "+15555550123").copy(
                    id = w1,
                    accountId = other,
                    participants = listOf(samOnWa),
                    lastActivityAt = now + 1.hours,
                ),
            ),
        )
    }

    @Test
    fun mergingTwoChatsMakesOneInboxRowThatSumsThem() =
        runTest {
            seed()
            val id = merges.merge(listOf(c1, w1))
            assertTrue(Merges.isMergedId(id))
            val parent = chats.get(id)!!
            assertEquals(3, parent.unreadCount)
            assertEquals(now + 1.hours, parent.lastActivityAt)
            assertEquals("Sam Ortiz", parent.title)
            assertEquals(accountId, parent.defaultSendAccount)
            assertEquals(ChatKind.DIRECT, parent.kind)
            assertEquals(setOf(c1, w1), merged.members(id).map { it.id }.toSet())
            // Only the merged chat is in the inbox; the members are inside it.
            assertEquals(listOf(id), chats.inbox().first().map { it.id })
            // Badges count the members on their own networks, never the merged row twice.
            assertEquals(3, chats.unreadTotals().first().total)
        }

    @Test
    fun aMessageInAMemberMovesTheMergedChat() =
        runTest {
            seed()
            val id = merges.merge(listOf(c1, w1))
            actions.setRead(id, true)
            assertEquals(0, chats.get(id)!!.unreadCount)
            assertEquals(0, chats.get(c1)!!.unreadCount)
            assertEquals(0, chats.get(w1)!!.unreadCount)
            val later = now + 2.hours
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m9", sentAt = later)))
            assertEquals(1, chats.get(id)!!.unreadCount)
            assertEquals(later, chats.get(id)!!.lastActivityAt)
        }

    @Test
    fun readingTheMergedChatReadsEveryMemberOnItsNetwork() =
        runTest {
            seed()
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1")))
            val id = merges.merge(listOf(c1, w1))
            actions.setRead(id, true)
            assertEquals(listOf(c1), connector.readMarkers.map { it.first })
            assertEquals(0, chats.unreadTotals().first().total)
        }

    @Test
    fun splittingTheLastPairDissolvesTheMergedChat() =
        runTest {
            seed()
            val id = merges.merge(listOf(c1, w1))
            merges.split(w1)
            assertNull(chats.get(id))
            assertNull(chats.get(c1)!!.mergedInto)
            assertNull(chats.get(w1)!!.mergedInto)
            assertEquals(
                setOf(c1, w1),
                chats
                    .inbox()
                    .first()
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun unmergingReturnsEveryMemberToTheInbox() =
        runTest {
            seed()
            val id = merges.merge(listOf(c1, w1))
            merges.unmerge(id)
            assertNull(chats.get(id))
            assertNull(chats.get(c1)!!.mergedInto)
            assertNull(chats.get(w1)!!.mergedInto)
            assertEquals(
                setOf(c1, w1),
                chats
                    .inbox()
                    .first()
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun deletingAMemberLeavesTheOtherOnItsOwn() =
        runTest {
            seed()
            val id = merges.merge(listOf(c1, w1))
            actions.delete(w1)
            assertNull(chats.get(id))
            assertNotNull(chats.get(c1))
            assertNull(chats.get(c1)!!.mergedInto)
        }

    @Test
    fun deletingTheMergedChatDeletesItsMembers() =
        runTest {
            seed()
            val id = merges.merge(listOf(c1, w1))
            actions.delete(id)
            assertNull(chats.get(id))
            assertNull(chats.get(c1))
            assertNull(chats.get(w1))
        }

    @Test
    fun mergingIntoAMergedChatAddsTheMember() =
        runTest {
            seed()
            applier.applyChats(listOf(chatSnapshot("c2", title = "Sam (old number)")))
            val id = merges.merge(listOf(c1, w1))
            val same = merges.merge(listOf(accountId.chat("c2"), id))
            assertEquals(id, same)
            assertEquals(3, merged.members(id).size)
        }

    @Test
    fun groupsAndSingleChatsAreRefused() =
        runTest {
            seed()
            applier.applyChats(listOf(chatSnapshot("g").copy(kind = ChatKind.GROUP, title = "Family")))
            val group = runCatching { merges.merge(listOf(c1, accountId.chat("g"))) }.exceptionOrNull()
            assertTrue(group is MergeRefusedException)
            assertTrue(runCatching { merges.merge(listOf(c1)) }.exceptionOrNull() is MergeRefusedException)
        }

    @Test
    fun aMemberWithoutAContactLinkTakesTheOthers() =
        runTest {
            seed()
            contacts.link(sam().id, ContactId("lookup-42"), "Sam Ortiz", "content://photo/42")
            merges.merge(listOf(c1, w1))
            val onWa = contacts.person(other.person("sam"))!!
            assertEquals(ContactId("lookup-42"), onWa.contactId)
            assertEquals("Sam Ortiz", onWa.name)
        }
}
