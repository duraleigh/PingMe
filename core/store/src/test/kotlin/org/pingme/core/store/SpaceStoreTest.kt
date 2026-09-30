// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceId
import org.pingme.core.model.SpaceKind

class SpaceStoreTest : StoreTest() {
    @Test
    fun spacesKeepChatOrderAndCustomSpacesSpanAccounts() =
        runTest {
            accounts.upsert(account("wa"))
            accounts.upsert(account("tg"))
            chats.upsert(chat("c1", "wa"))
            chats.upsert(chat("c2", "wa"))
            chats.upsert(chat("c3", "tg"))
            val community =
                Space(
                    SpaceId("s1"),
                    AccountId("wa"),
                    "Neighbours",
                    SpaceKind.WHATSAPP_COMMUNITY,
                    listOf(ChatId("c2"), ChatId("c1")),
                )
            val family = Space(SpaceId("s2"), null, "Family", SpaceKind.CUSTOM, listOf(ChatId("c3"), ChatId("c1")))
            chats.upsertSpace(community)
            chats.upsertSpace(family)
            assertEquals(listOf(family, community), chats.spaces().first())

            chats.upsertSpace(family.copy(chatIds = listOf(ChatId("c3"))))
            assertEquals(listOf(ChatId("c3")), chats.space(SpaceId("s2"))?.chatIds)
        }

    @Test
    fun deletingAChatLeavesTheSpaceAndDeletingASpaceLeavesTheChats() =
        runTest {
            accounts.upsert(account("a"))
            chats.upsert(chat("c1", "a"))
            chats.upsert(chat("c2", "a"))
            chats.upsertSpace(Space(SpaceId("s"), null, "Mine", SpaceKind.CUSTOM, listOf(ChatId("c1"), ChatId("c2"))))
            chats.delete(ChatId("c1"))
            assertEquals(listOf(ChatId("c2")), chats.space(SpaceId("s"))?.chatIds)
            chats.deleteSpace(SpaceId("s"))
            assertNull(chats.space(SpaceId("s")))
            assertEquals(listOf("c2"), chats.all().first().map { it.id.value })
        }
}
