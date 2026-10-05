// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Quote
import org.pingme.core.store.PinnedMessageRepository
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MessageActionsTest : ServiceTest() {
    private val connector = FakeConnector()
    private val actions by lazy {
        MessageActions(
            chats,
            messages,
            PinnedMessageRepository(db),
            accounts,
            ConnectorRegistry(
                mapOf(NetworkId.DEMO to connector),
            ),
            applier,
            clock,
            scheduledSends,
            alarm,
            settings,
            org.pingme.core.service.links
                .CleanLinks
                .fromAssets(context),
            QuietPreviews(context),
        )
    }
    private val scheduledSends by lazy {
        org.pingme.core.store
            .ScheduledSendRepository(db)
    }
    private val alarm by lazy {
        QuietAlarm(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext(),
            scheduledSends,
            clock,
        )
    }
    private val chatId get() = accountId.chat("c1")

    private suspend fun seed() {
        accounts.upsert(account())
        applier.applyChats(listOf(chatSnapshot(unread = 2)))
    }

    @Test
    fun aSentMessageReplacesItsPendingBubble() =
        runTest {
            seed()
            connector.sendResult =
                { draft -> SendResult.Sent(messageSnapshot("net-1", body = draft.body!!, outgoing = true)) }
            val sent = actions.send(chatId, "On my way")
            val stored = messages.latest(chatId, 10).first()
            assertEquals(listOf("net-1"), stored.map { it.id.value.substringAfter('/') })
            assertEquals("On my way", sent.body)
            assertEquals("sending clears the badge", 0, chats.get(chatId)!!.unreadCount)
        }

    @Test
    fun aSentMessageIsShownUnderItsPendingId() =
        runTest {
            seed()
            var sending: org.pingme.core.model.MessageId? = null
            connector.sendResult = { draft ->
                sending = draft.clientId
                SendResult.Sent(messageSnapshot("net-1", body = draft.body!!, outgoing = true))
            }
            val sent = actions.send(chatId, "hi")
            // The chat draws the network's copy under the pending bubble's id, so it is one bubble throughout.
            assertEquals(sending, actions.shownAs(sent.id))
            assertEquals("an id that was never pending is its own", sending, actions.shownAs(sending!!))
        }

    @Test
    fun actingOnTheSendingCopyActsOnTheSentMessage() =
        runTest {
            seed()
            var sending: org.pingme.core.model.MessageId? = null
            connector.sendResult = { draft ->
                sending = draft.clientId
                SendResult.Sent(messageSnapshot("net-1", body = draft.body!!, outgoing = true))
            }
            val sent = actions.send(chatId, "helo")
            // What a bubble held just before the network's copy replaced it still carries.
            val held = sent.copy(id = sending!!, status = MessageStatus.Sending)
            actions.pin(held)
            assertEquals(listOf(sent.id), PinnedMessageRepository(db).pinned(chatId).first().map { it.id })
            actions.deleteForMe(listOf(held))
            assertEquals(null, messages.get(sent.id))
        }

    @Test
    fun aFailedSendStaysWithItsReasonAndCanBeRetried() =
        runTest {
            seed()
            connector.sendResult = { SendResult.Failed("No signal", retryable = true) }
            val failed = actions.send(chatId, "Hello")
            assertEquals(MessageStatus.Failed("No signal"), failed.status)
            assertEquals(listOf(failed), messages.latest(chatId, 10).first())

            connector.sendResult = { SendResult.Sent(messageSnapshot("net-2", body = "Hello", outgoing = true)) }
            actions.retry(failed)
            assertEquals(listOf("net-2"), messages.latest(chatId, 10).first().map { it.id.value.substringAfter('/') })
        }

    @Test
    fun aNetworkThatCarriesOnePictureAMessageGetsOneMessagePerPicture() =
        runTest {
            // Google Messages keeps one picture per message and drops the rest (owner,
            // 2026-10-05: two screenshots sent, one arrived).
            seed()
            connector.capabilities = connector.capabilities.copy(attachmentsPerMessage = 1)
            val one = File.createTempFile("one", ".jpg").apply { writeBytes(ByteArray(PHOTO_BYTES)) }
            val two = File.createTempFile("two", ".jpg").apply { writeBytes(ByteArray(PHOTO_BYTES)) }
            val files =
                listOf(one, two).map { OutgoingAttachment(it.path, "image/jpeg", AttachmentKind.IMAGE, it.name, null) }
            val drafts = mutableListOf<OutgoingMessage>()
            connector.sendResult = { draft ->
                drafts += draft
                SendResult.Failed("No signal", retryable = true)
            }
            val first = actions.send(chatId, "two options", attachments = files)
            assertEquals(2, drafts.size)
            assertEquals("two options", drafts[0].body)
            assertEquals(listOf(files[0]), drafts[0].attachments)
            assertEquals(null, drafts[1].body)
            assertEquals(listOf(files[1]), drafts[1].attachments)
            assertEquals("two options", first.body)
            assertEquals(1, first.attachments.size)
        }

    @Test
    fun aPhotoShowsOnItsPendingBubbleAndGoesAgainOnRetry() =
        runTest {
            seed()
            val photo = File.createTempFile("photo", ".jpg").apply { writeBytes(ByteArray(PHOTO_BYTES)) }
            val file = OutgoingAttachment(photo.path, "image/jpeg", AttachmentKind.IMAGE, "photo.jpg", caption = null)
            val drafts = mutableListOf<OutgoingMessage>()
            connector.sendResult = { draft ->
                drafts += draft
                SendResult.Failed("No signal", retryable = true)
            }
            val failed = actions.send(chatId, "", attachments = listOf(file))
            assertEquals(MessageKind.IMAGE, failed.kind)
            assertEquals("a photo alone has no text", null, failed.body)
            assertEquals(PHOTO_BYTES.toLong(), failed.attachments.single().sizeBytes)
            assertEquals(photo.path, failed.attachments.single().localPath)

            actions.retry(failed)
            assertEquals(listOf(listOf(file), listOf(file)), drafts.map { it.attachments })
            assertTrue("nothing is left uploading", actions.progress.value.isEmpty())
        }

    @Test
    fun aReplyCarriesItsQuote() =
        runTest {
            seed()
            applier.apply(
                org.pingme.core.connector.ConnectorEvent.NewMessage(
                    accountId,
                    messageSnapshot("m1", body = "Coming tonight?"),
                ),
            )
            val original = messages.latest(chatId, 1).first().single()
            var sentQuote: Quote? = null
            connector.sendResult = { draft ->
                sentQuote = draft.quote
                SendResult.Failed("stop here", retryable = false)
            }
            actions.send(chatId, "Yes", replyTo = original, replyToName = "Sam")
            assertEquals(Quote("Sam", "Coming tonight?"), sentQuote)
        }

    @Test
    fun pinsAreLocalAndNewestFirst() =
        runTest {
            seed()
            applier.apply(
                org.pingme.core.connector.ConnectorEvent.NewMessage(
                    accountId,
                    messageSnapshot(
                        "a",
                        sentAt =
                            now - 5.minutes,
                    ),
                ),
            )
            applier.apply(
                org.pingme.core.connector.ConnectorEvent
                    .NewMessage(accountId, messageSnapshot("b")),
            )
            val (b, a) = messages.latest(chatId, 2).first()
            actions.pin(a)
            actions.pin(b)
            assertEquals(listOf(b.id, a.id), PinnedMessageRepository(db).pinned(chatId).first().map { it.id })
            actions.unpin(b.id)
            assertEquals(listOf(a.id), PinnedMessageRepository(db).pinned(chatId).first().map { it.id })
        }

    @Test
    fun olderHistoryLoadsUntilThereIsNoMore() =
        runTest {
            seed()
            connector.history = (1..3).map { messageSnapshot("h$it", sentAt = now - it.minutes) }
            assertFalse("fewer than a page means that was all", actions.loadOlder(chatId, count = 5))
            assertEquals(3, messages.latest(chatId, 10).first().size)
            assertTrue("older history is never unread", chats.get(chatId)!!.unreadCount == 2)
        }

    @Test
    fun reactionsReplaceYourOwnAndCanBeTakenBack() =
        runTest {
            seed()
            applier.apply(
                org.pingme.core.connector.ConnectorEvent
                    .NewMessage(accountId, messageSnapshot("m1")),
            )
            applier.apply(
                org.pingme.core.connector.ConnectorEvent
                    .NewMessage(accountId, messageSnapshot("mine", outgoing = true)),
            )

            fun latest() = kotlinx.coroutines.runBlocking { messages.get(accountId.message("m1"))!! }
            actions.react(latest(), "❤️")
            actions.react(latest(), "😂")
            assertEquals(listOf("😂"), latest().reactions.map { it.emoji })
            actions.react(latest(), "😂", remove = true)
            assertTrue(latest().reactions.isEmpty())
        }

    @Test
    fun deletingForMeRemovesAndForEveryoneLeavesAPlaceholder() =
        runTest {
            seed()
            applier.apply(
                org.pingme.core.connector.ConnectorEvent
                    .NewMessage(accountId, messageSnapshot("a", outgoing = true)),
            )
            applier.apply(
                org.pingme.core.connector.ConnectorEvent
                    .NewMessage(accountId, messageSnapshot("b", outgoing = true)),
            )
            actions.deleteForMe(listOf(messages.get(accountId.message("a"))!!))
            assertEquals(null, messages.get(accountId.message("a")))
            actions.deleteForEveryone(messages.get(accountId.message("b"))!!)
            val b = messages.get(accountId.message("b"))!!
            assertTrue(b.deletedForEveryone && b.body == null)
        }

    @Test
    fun editingWhereTheNetworkCannotSaysWhy() =
        runTest {
            seed()
            applier.apply(
                org.pingme.core.connector.ConnectorEvent
                    .NewMessage(accountId, messageSnapshot("a", outgoing = true)),
            )
            val failure =
                runCatching { actions.edit(messages.get(accountId.message("a"))!!, "new") }.exceptionOrNull()
            assertTrue(failure is org.pingme.core.connector.UnsupportedCapabilityException)
            assertEquals("the text stays as it was", "hi", messages.get(accountId.message("a"))!!.body)
        }

    @Test
    fun aScheduledMessageWaitsAndCanBeChangedOrCancelled() =
        runTest {
            seed()
            val later = now + 3.hours
            val waiting = actions.schedule(chatId, "Happy birthday!", later)
            assertEquals(MessageStatus.Scheduled(later), messages.get(waiting.id)!!.status)
            assertEquals(later, scheduledSends.due(later).single().sendAt)
            assertTrue("the wake-up is set", alarm.armed > 0)

            actions.edit(waiting, "Happy birthday!!")
            val draft =
                Json.decodeFromString(
                    OutgoingMessage.serializer(),
                    scheduledSends.due(later).single().payloadJson,
                )
            assertEquals("Happy birthday!!", draft.body)
            assertEquals(MessageStatus.Scheduled(later), messages.get(waiting.id)!!.status)

            actions.cancelScheduled(messages.get(waiting.id)!!)
            assertEquals(null, messages.get(waiting.id))
            assertTrue(scheduledSends.due(later).isEmpty())
        }

    @Test
    fun sendNowSendsAScheduledMessageStraightAway() =
        runTest {
            seed()
            connector.sendResult = { SendResult.Sent(messageSnapshot("net-5", body = it.body!!, outgoing = true)) }
            val waiting = actions.schedule(chatId, "Early", now + 1.hours)
            actions.sendNow(waiting)
            assertEquals(listOf("net-5"), messages.latest(chatId, 10).first().map { it.id.value.substringAfter('/') })
            assertTrue(scheduledSends.due(now + 2.hours).isEmpty())
        }

    @Test
    fun forwardingTakesTheFilesToo() =
        runTest {
            seed()
            val photo = File.createTempFile("photo", ".jpg").apply { writeBytes(ByteArray(PHOTO_BYTES)) }
            val drafts = mutableListOf<OutgoingMessage>()
            connector.sendResult = { draft ->
                drafts += draft
                SendResult.Failed("No signal", retryable = true)
            }
            val original =
                actions.send(
                    chatId,
                    "",
                    attachments =
                        listOf(
                            OutgoingAttachment(photo.path, "image/jpeg", AttachmentKind.IMAGE, "p.jpg", null),
                        ),
                )
            actions.forward(original, chatId)
            assertEquals(listOf(photo.path), drafts.last().attachments.map { it.localPath })
        }

    @Test
    fun typingOffSendsNoTypingEvents() =
        runTest {
            seed()
            actions.setTyping(chatId, true)
            settings.updateApp { it.copy(privacy = it.privacy.copy(typing = false)) }
            actions.setTyping(chatId, true)
            assertEquals("only the first, before it was turned off", listOf(true), connector.typingSent)
        }

    private companion object {
        const val PHOTO_BYTES = 2048
    }
}
