// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.MessageId
import org.pingme.core.model.PersonId
import kotlin.time.Duration.Companion.minutes

class ChatStoreTest : StoreTest() {
    @Test
    fun upsertKeepsEveryFieldAndParticipantOrder() =
        runTest {
            accounts.upsert(account("a"))
            val c =
                chat("c", "a").copy(
                    participants = listOf(PersonId("z"), PersonId("a"), PersonId("m")),
                    isPinned = true,
                    pinOrder = 3,
                    isMuted = true,
                    muteUntil = now + 5.minutes,
                    isObscured = true,
                    folder = ChatFolder.PRIMARY,
                    avatarSource = AvatarSource.Network(AccountId("a")),
                    nameOverride = "Sam (work)",
                    defaultSendAccount = AccountId("a"),
                )
            chats.upsert(c)
            assertEquals(c, chats.get(ChatId("c")))

            val fewer = c.copy(participants = listOf(PersonId("m")))
            chats.upsert(fewer)
            assertEquals(fewer, chats.chat(ChatId("c")).first())
        }

    @Test
    fun updateChangesOneChatAtomically() =
        runTest {
            accounts.upsert(account("a"))
            chats.upsert(chat("c", "a"))
            val pinned = chats.update(ChatId("c")) { it.copy(isPinned = true, pinOrder = 0) }
            assertEquals(true, pinned?.isPinned)
            assertEquals(pinned, chats.get(ChatId("c")))
            assertNull(chats.update(ChatId("missing")) { it })
        }

    @Test
    fun inboxShowsOnlyVisibleChatsNewestFirst() =
        runTest {
            accounts.upsert(account("a"))
            accounts.upsert(account("hidden", showInInbox = false))
            chats.upsert(chat("old", "a", activity = now - 10.minutes))
            chats.upsert(chat("new", "a", activity = now))
            chats.upsert(chat("archived", "a").copy(isArchived = true))
            chats.upsert(chat("low", "a").copy(isLowPriority = true))
            chats.upsert(chat("request", "a").copy(folder = ChatFolder.REQUESTS))
            chats.upsert(chat("general", "a", activity = now - 5.minutes).copy(folder = ChatFolder.GENERAL))
            chats.upsert(chat("topic", "a", activity = now - 20.minutes).copy(folder = ChatFolder.TOPIC))
            chats.upsert(chat("other-account", "hidden"))

            assertEquals(listOf("new", "general", "old", "topic"), chats.inbox().first().map { it.id.value })

            settings.setInstagramShowGeneral(false)
            assertEquals(listOf("new", "old", "topic"), chats.inbox().first().map { it.id.value })

            assertEquals(listOf("archived"), chats.archived().first().map { it.id.value })
            assertEquals(listOf("low"), chats.lowPriority().first().map { it.id.value })
            assertEquals(listOf("request"), chats.folder(ChatFolder.REQUESTS).first().map { it.id.value })
            assertEquals(listOf("other-account"), chats.byAccount(AccountId("hidden")).first().map { it.id.value })
        }

    @Test
    fun deletingAChatRemovesItsMessages() =
        runTest {
            accounts.upsert(account("a"))
            chats.upsert(chat("c", "a"))
            messages.upsert(message("m", "c"))
            chats.delete(ChatId("c"))
            assertNull(chats.get(ChatId("c")))
            assertNull(messages.get(MessageId("m")))
        }
}
