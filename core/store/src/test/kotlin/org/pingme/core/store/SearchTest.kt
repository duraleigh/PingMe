// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.ChatId
import org.pingme.core.model.MessageId
import org.pingme.core.store.db.PingMeDatabase
import kotlin.time.Duration.Companion.minutes

/** The FTS5 message index (BUILD_PLAN.md P1.2) and the triggers that keep it in sync. */
class SearchTest : StoreTest() {
    private suspend fun seed() {
        accounts.upsert(account("a"))
        chats.upsert(chat("c1", "a"))
        chats.upsert(chat("c2", "a"))
    }

    private suspend fun ids(
        text: String,
        chatId: String? = null,
    ) = messages.search(text, chatId?.let(::ChatId)).first().map { it.id.value }

    @Test
    fun findsByBodyWithPrefixesAndAllWords() =
        runTest {
            seed()
            messages.upsert(message("m1", "c1", body = "Are you still coming tonight?"))
            messages.upsert(message("m2", "c1", body = "Coming at seven"))
            assertEquals(listOf("m1"), ids("tonight"))
            assertEquals(setOf("m1", "m2"), ids("com").toSet())
            assertEquals(listOf("m1"), ids("coming still"))
            assertEquals(emptyList<String>(), ids("coming tomorrow"))
        }

    @Test
    fun matchingIgnoresCaseAndAccents() =
        runTest {
            seed()
            messages.upsert(message("m", "c1", body = "Meet at the Café"))
            assertEquals(listOf("m"), ids("cafe"))
            assertEquals(listOf("m"), ids("CAFÉ"))
        }

    @Test
    fun findsBySenderNameAndFollowsRenames() =
        runTest {
            seed()
            messages.upsert(message("m", "c1", body = "hi", sender = "p-sam"))
            assertEquals(emptyList<String>(), ids("ortiz"))
            contacts.upsert(person("p-sam", "a", "Sam Ortiz"))
            assertEquals(listOf("m"), ids("ortiz"))
            contacts.upsert(person("p-sam", "a", "Samantha Reyes"))
            assertEquals(emptyList<String>(), ids("ortiz"))
            assertEquals(listOf("m"), ids("reyes"))
        }

    @Test
    fun findsByAttachmentName() =
        runTest {
            seed()
            messages.upsert(message("m", "c1", body = null, attachments = listOf(attachment("x", "Lease-2026.pdf"))))
            assertEquals(listOf("m"), ids("lease"))
            messages.upsert(message("m", "c1", body = null, attachments = listOf(attachment("y", "photo.jpg"))))
            assertEquals(emptyList<String>(), ids("lease"))
            assertEquals(listOf("m"), ids("photo"))
        }

    @Test
    fun editsAndDeletesUpdateTheIndex() =
        runTest {
            seed()
            messages.upsert(message("m", "c1", body = "first draft"))
            messages.upsert(message("m", "c1", body = "final version"))
            assertEquals(emptyList<String>(), ids("draft"))
            assertEquals(listOf("m"), ids("final"))
            messages.delete(MessageId("m"))
            assertEquals(emptyList<String>(), ids("final"))
        }

    @Test
    fun searchesOneChatNewestFirst() =
        runTest {
            seed()
            messages.upsert(message("old", "c1", body = "pizza", sentAt = now - 2.minutes))
            messages.upsert(message("new", "c1", body = "pizza", sentAt = now))
            messages.upsert(message("elsewhere", "c2", body = "pizza", sentAt = now - 1.minutes))
            assertEquals(listOf("new", "elsewhere", "old"), ids("pizza"))
            assertEquals(listOf("new", "old"), ids("pizza", chatId = "c1"))
        }

    @Test
    fun oddInputIsSafe() =
        runTest {
            seed()
            messages.upsert(message("m", "c1", body = "rock AND roll (live) - 2026 \"tour\""))
            listOf("\"", "*", "AND", "NOT", "(", "-", "tour\"", "roll*", "a:b", "NEAR(").forEach { ids(it) }
            assertEquals(emptyList<String>(), ids("   "))
            assertEquals(listOf("m"), ids("\"tour"))
        }

    @Test
    fun aDatabaseThatLostItsIndexRebuildsItOnOpen() =
        runTest {
            val file = temp.root.resolve("reopen.db").absolutePath
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()

            fun open() = PingMeDatabase.configure(Room.databaseBuilder(context, PingMeDatabase::class.java, file))

            val first = open()
            val firstMessages = MessageRepository(first)
            AccountRepository(first).upsert(account("a"))
            ChatRepository(first, settings, kotlin.time.Clock.System).upsert(chat("c", "a"))
            firstMessages.upsert(message("m", "c", body = "survives"))
            first.useWriterConnection { it.usePrepared("DROP TABLE message_fts") { stmt -> stmt.step() } }
            first.close()

            val second = open()
            assertEquals(listOf("m"), MessageRepository(second).search("survives").first().map { it.id.value })
            second.close()
        }
}
