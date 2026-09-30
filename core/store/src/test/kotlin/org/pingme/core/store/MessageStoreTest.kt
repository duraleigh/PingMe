// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.ChatId
import org.pingme.core.model.LinkPreview
import org.pingme.core.model.LinkPreviewSource
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.PersonId
import org.pingme.core.model.Quote
import org.pingme.core.model.Reaction
import kotlin.time.Duration.Companion.minutes

class MessageStoreTest : StoreTest() {
    private suspend fun seed() {
        accounts.upsert(account("a"))
        chats.upsert(chat("c", "a"))
    }

    @Test
    fun upsertKeepsEveryField() =
        runTest {
            seed()
            val m =
                message("m", "c", attachments = listOf(attachment("z", "b.pdf"), attachment("y", "a.pdf"))).copy(
                    replyTo = MessageId("earlier"),
                    quote = Quote("Sam", "Are you still..."),
                    editedAt = now,
                    status = MessageStatus.Failed("No signal"),
                    reactions = listOf(Reaction("❤️", PersonId("p-sam"), now)),
                    linkPreview =
                        LinkPreview(
                            "https://x.test/?utm=1",
                            "https://x.test/",
                            "X",
                            null,
                            null,
                            now,
                            LinkPreviewSource.NETWORK,
                        ),
                    isOutgoing = true,
                )
            messages.upsert(m)
            assertEquals(m, messages.get(MessageId("m")))
        }

    @Test
    fun updatingAMessageKeepsItsRowAndReplacesItsParts() =
        runTest {
            seed()
            messages.upsert(message("m", "c", attachments = listOf(attachment("x", "old.pdf"))))
            val rowId = db.messageDao().rowIdFor("m")
            val edited = message("m", "c", body = "edited", attachments = listOf(attachment("y", "new.pdf")))
            messages.upsert(edited)
            assertEquals(rowId, db.messageDao().rowIdFor("m"))
            assertEquals(edited, messages.get(MessageId("m")))
        }

    @Test
    fun latestIsNewestFirstAndLimited() =
        runTest {
            seed()
            messages.upsert(message("m1", "c", sentAt = now - 2.minutes))
            messages.upsert(message("m3", "c", sentAt = now))
            messages.upsert(message("m2", "c", sentAt = now - 1.minutes))
            assertEquals(listOf("m3", "m2"), messages.latest(ChatId("c"), limit = 2).first().map { it.id.value })
        }

    @Test
    fun statusAndReactionsChangeInPlace() =
        runTest {
            seed()
            messages.upsert(message("m", "c"))
            messages.updateStatus(MessageId("m"), MessageStatus.Read)
            messages.addReaction(MessageId("m"), Reaction("👍", PersonId("p-dad"), now))
            messages.addReaction(MessageId("m"), Reaction("😂", PersonId("p-mom"), now + 1.minutes))
            messages.removeReaction(MessageId("m"), PersonId("p-dad"), "👍")
            val m = messages.message(MessageId("m")).first()
            assertEquals(MessageStatus.Read, m?.status)
            assertEquals(listOf(Reaction("😂", PersonId("p-mom"), now + 1.minutes)), m?.reactions)
        }

    @Test
    fun deletingAMessageRemovesItsAttachmentsAndReactions() =
        runTest {
            seed()
            messages.upsert(
                message("m", "c", attachments = listOf(attachment("x", "a.pdf")))
                    .copy(reactions = listOf(Reaction("❤️", PersonId("p"), now))),
            )
            messages.delete(MessageId("m"))
            assertNull(messages.get(MessageId("m")))
            // Re-adding the same ids works only if the old attachment and reaction rows are gone.
            messages.upsert(
                message("m", "c", attachments = listOf(attachment("x", "a.pdf")))
                    .copy(reactions = listOf(Reaction("❤️", PersonId("p"), now))),
            )
            assertEquals(1, messages.get(MessageId("m"))?.attachments?.size)
        }
}
