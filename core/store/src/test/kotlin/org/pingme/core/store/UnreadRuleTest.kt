// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceId
import org.pingme.core.model.SpaceKind
import kotlin.time.Duration.Companion.minutes

/** The one unread counting rule (BUILD_PLAN.md P1.2, UI_DESIGN.md 6.4, 6.5, 10.7). */
class UnreadRuleTest : StoreTest() {
    private suspend fun total() = chats.unreadTotals().first().total

    @Test
    fun countsVisibleUnreadChats() =
        runTest {
            accounts.upsert(account("a"))
            chats.upsert(chat("c1", "a", unread = 2))
            chats.upsert(chat("c2", "a", unread = 3))
            chats.upsert(chat("read", "a", unread = 0))
            assertEquals(5, total())
        }

    @Test
    fun archivedLowPriorityAndMutedChatsNeverCount() =
        runTest {
            accounts.upsert(account("a"))
            chats.upsert(chat("counted", "a", unread = 1))
            chats.upsert(chat("archived", "a", unread = 10).copy(isArchived = true))
            chats.upsert(chat("low", "a", unread = 10).copy(isLowPriority = true))
            chats.upsert(chat("muted", "a", unread = 10).copy(isMuted = true, muteUntil = null))
            chats.upsert(chat("muted-for-now", "a", unread = 10).copy(isMuted = true, muteUntil = now + 1.minutes))
            assertEquals(1, total())
        }

    @Test
    fun aSpaceYouMadeCountsTheChatsYouAddedToIt() =
        runTest {
            accounts.upsert(account("a"))
            chats.upsert(chat("added", "a", unread = 2))
            chats.upsert(chat("quiet", "a", unread = 5).copy(isMuted = true, muteUntil = null))
            chats.upsert(chat("outside", "a", unread = 7))
            val mine = SpaceId("mine")
            chats.upsertSpace(Space(mine, null, "Mine", SpaceKind.CUSTOM, listOf(ChatId("added"), ChatId("quiet"))))
            assertEquals("only the unmuted chat added to it", 2, chats.unreadTotals().first().bySpace[mine])
        }

    @Test
    fun anEndedTimedMuteCountsAgain() =
        runTest {
            accounts.upsert(account("a"))
            chats.upsert(chat("mute-over", "a", unread = 4).copy(isMuted = true, muteUntil = now - 1.minutes))
            assertEquals(4, total())
        }

    @Test
    fun requestsNeverCountAndGeneralFollowsItsSwitch() =
        runTest {
            accounts.upsert(account("ig", NetworkId.INSTAGRAM))
            chats.upsert(chat("primary", "ig", unread = 1).copy(folder = ChatFolder.PRIMARY))
            chats.upsert(chat("general", "ig", unread = 2).copy(folder = ChatFolder.GENERAL))
            chats.upsert(chat("request", "ig", unread = 4).copy(folder = ChatFolder.REQUESTS))
            assertEquals(3, total())
            settings.setInstagramShowGeneral(false)
            assertEquals(1, total())
        }

    @Test
    fun accountsHiddenFromTheInboxNeverCount() =
        runTest {
            accounts.upsert(account("personal", NetworkId.MESSENGER))
            accounts.upsert(account("page", NetworkId.FBPAGE, showInInbox = false))
            chats.upsert(chat("friend", "personal", unread = 1))
            chats.upsert(chat("customer", "page", unread = 9))
            assertEquals(1, total())
        }

    @Test
    fun totalsBreakDownByAccountNetworkAndSpace() =
        runTest {
            accounts.upsert(account("wa1", NetworkId.WHATSAPP))
            accounts.upsert(account("wa2", NetworkId.WHATSAPP))
            accounts.upsert(account("tg", NetworkId.TELEGRAM))
            chats.upsert(chat("a", "wa1", unread = 1).copy(spaceId = SpaceId("community")))
            chats.upsert(chat("b", "wa1", unread = 2))
            chats.upsert(chat("c", "wa2", unread = 3).copy(spaceId = SpaceId("community")))
            chats.upsert(chat("d", "tg", unread = 4))

            val totals = chats.unreadTotals().first()
            assertEquals(10, totals.total)
            assertEquals(mapOf(AccountId("wa1") to 3, AccountId("wa2") to 3, AccountId("tg") to 4), totals.byAccount)
            assertEquals(mapOf(NetworkId.WHATSAPP to 6, NetworkId.TELEGRAM to 4), totals.byNetwork)
            assertEquals(mapOf(SpaceId("community") to 4), totals.bySpace)
        }

    @Test
    fun noUnreadChatsMeansNone() =
        runTest {
            assertEquals(UnreadTotals.NONE, chats.unreadTotals().first())
        }
}
