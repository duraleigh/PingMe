// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.AttachmentId
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
    fun updatingAMessageKeepsDownloadedFiles() =
        runTest {
            seed()
            messages.upsert(message("m", "c", attachments = listOf(attachment("x", "pic.jpg"))))
            messages.setAttachmentLocalPath(AttachmentId("x"), "/data/pic.jpg")
            // The network's updated copy (a reaction, a status) names the file but has no path.
            messages.upsert(message("m", "c", attachments = listOf(attachment("x", "pic.jpg"))))
            assertEquals(
                "/data/pic.jpg",
                messages
                    .get(MessageId("m"))!!
                    .attachments
                    .single()
                    .localPath,
            )
        }

    @Test
    fun updatingAMessageKeepsItsFetchedLinkPreview() =
        runTest {
            seed()
            messages.upsert(message("m", "c", body = "see https://e.com/x"))
            val preview =
                org.pingme.core.model.LinkPreview(
                    "https://e.com/x",
                    "https://e.com/x",
                    "Title",
                    null,
                    null,
                    now,
                    org.pingme.core.model.LinkPreviewSource.LOCAL,
                )
            messages.upsert(messages.get(MessageId("m"))!!.copy(linkPreview = preview))
            // The network's delivered or read copy carries no preview; the fetched one stays.
            messages.upsert(message("m", "c", body = "see https://e.com/x"))
            assertEquals("Title", messages.get(MessageId("m"))!!.linkPreview?.title)
        }

    @Test
    fun junkIsStandInsOlderThanTheCutOffAndEmptyBubbles() =
        runTest {
            seed()
            val old = message("tmp-old", "c", body = "ghost").copy(networkRemoteId = "tmp/1", sentAt = now - 30.minutes)
            val fresh = message("tmp-new", "c", body = "pending").copy(networkRemoteId = "tmp/2", sentAt = now)
            val empty = message("empty", "c", body = null)
            val real = message("real", "c", body = "hello")
            listOf(old, fresh, empty, real).forEach { messages.upsert(it) }
            assertEquals(2, messages.deleteJunk("tmp/", now - 10.minutes))
            assertNull(messages.get(MessageId("tmp-old")))
            assertNull(messages.get(MessageId("empty")))
            assertEquals("pending", messages.get(MessageId("tmp-new"))!!.body)
            assertEquals("hello", messages.get(MessageId("real"))!!.body)
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
    fun eachChatPreviewsItsNewestMessageAndWhoSentIt() =
        runTest {
            seed()
            chats.upsert(chat("d", "a"))
            contacts.upsert(person("p-sam", "a", "Sam Ortiz"))
            messages.upsert(message("m1", "c", body = "older", sentAt = now - 2.minutes))
            messages.upsert(message("m2", "c", body = "newest"))
            // Backfilled history arrives later but is older: it must not become the preview.
            messages.upsert(message("m0", "c", body = "ancient", sentAt = now - 9.minutes))
            messages.upsert(message("m3", "d", body = "from nobody we know", sender = "p-unknown"))

            val last = messages.lastMessages().first()
            assertEquals("newest", last.getValue(ChatId("c")).body)
            assertEquals("Sam Ortiz", last.getValue(ChatId("c")).senderName)
            assertNull(last.getValue(ChatId("d")).senderName)
            assertNull("no you yet", messages.selfIn(ChatId("c")))
            messages.upsert(message("m4", "c", sender = "p-me").copy(isOutgoing = true))
            assertEquals(PersonId("p-me"), messages.selfIn(ChatId("c")))
            // Your last message's mark follows it, for the inbox row (UI_DESIGN.md 3.1).
            messages.updateStatus(MessageId("m4"), MessageStatus.Read)
            val mine = messages.lastMessages().first().getValue(ChatId("c"))
            assertEquals(MessageStatus.Read, mine.status)
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

    @Test
    fun aReadReceiptMarksEarlierOutgoingMessagesRead() =
        runTest {
            seed()
            val out = { id: String, at: kotlin.time.Instant, status: MessageStatus ->
                message(id, "c", sentAt = at).copy(isOutgoing = true, status = status)
            }
            messages.upsert(out("o1", now - 3.minutes, MessageStatus.Delivered))
            messages.upsert(out("o2", now - 2.minutes, MessageStatus.Sent))
            messages.upsert(message("in", "c", sentAt = now - 1.minutes))
            messages.upsert(out("o3", now, MessageStatus.Delivered))
            messages.upsert(out("failed", now - 4.minutes, MessageStatus.Failed("x")))
            messages.markOutgoingRead(ChatId("c"), MessageId("o2"))

            suspend fun status(id: String) = messages.get(MessageId(id))?.status
            assertEquals(MessageStatus.Read, status("o1"))
            assertEquals(MessageStatus.Read, status("o2"))
            assertEquals(MessageStatus.Delivered, status("in"))
            assertEquals(MessageStatus.Delivered, status("o3"))
            assertEquals(MessageStatus.Failed("x"), status("failed"))
        }

    @Test
    fun aDownloadedAttachmentRemembersWhereItIs() =
        runTest {
            seed()
            messages.upsert(message("m", "c", attachments = listOf(attachment("x", "a.pdf"))))
            messages.setAttachmentLocalPath(
                org.pingme.core.model
                    .AttachmentId("x"),
                "/files/a.pdf",
            )
            assertEquals(
                "/files/a.pdf",
                messages
                    .attachment(
                        org.pingme.core.model
                            .AttachmentId("x"),
                    )?.localPath,
            )
        }
}
