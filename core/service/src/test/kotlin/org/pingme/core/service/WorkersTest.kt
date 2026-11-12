// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.attachment
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.ScheduledSend
import org.pingme.core.service.work.HistoryBackfillWorker
import org.pingme.core.service.work.MediaDownloadWorker
import org.pingme.core.service.work.ScheduledSendWorker
import org.pingme.core.store.ScheduledSendRepository
import kotlin.time.Duration.Companion.minutes

class WorkersTest : ServiceTest() {
    private val connector = FakeConnector()
    private val scheduled by lazy { ScheduledSendRepository(db) }
    private val registry get() = ConnectorRegistry(mapOf(NetworkId.DEMO to connector))

    private val factory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? =
                when (workerClassName) {
                    HistoryBackfillWorker::class.java.name -> {
                        HistoryBackfillWorker(
                            appContext,
                            workerParameters,
                            registry,
                            accounts,
                            messages,
                            chats,
                            applier,
                        )
                    }

                    MediaDownloadWorker::class.java.name -> {
                        MediaDownloadWorker(
                            appContext,
                            workerParameters,
                            MediaFetcher(appContext, registry, accounts, messages, keeper, router, scope),
                        )
                    }

                    ScheduledSendWorker::class.java.name -> {
                        ScheduledSendWorker(
                            appContext,
                            workerParameters,
                            registry,
                            accounts,
                            messages,
                            scheduled,
                            applier,
                            clock,
                            QuietAlarm(appContext, scheduled, clock),
                            router,
                            QuietPreviews(appContext),
                        )
                    }

                    else -> {
                        null
                    }
                }
        }

    private suspend fun seed() {
        accounts.upsert(account())
        applier.applyChats(listOf(chatSnapshot()))
    }

    @Test
    fun backfillPagesThroughHistoryOldestLast() =
        runTest {
            seed()
            connector.history = (1..120).map { messageSnapshot("h$it", sentAt = now - it.minutes) }
            val worker =
                TestListenableWorkerBuilder<HistoryBackfillWorker>(context)
                    .setWorkerFactory(factory)
                    .setInputData(workDataOf(HistoryBackfillWorker.KEY_CHAT to accountId.chat("c1").value))
                    .build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            (1..120).forEach { assertEquals("h$it", messages.get(accountId.message("h$it"))?.networkRemoteId) }
        }

    @Test
    fun backfillAsksNothingForAChatDeletedHere() =
        runTest {
            seed()
            connector.history = (1..120).map { messageSnapshot("h$it", sentAt = now - it.minutes) }
            chats.hide(accountId.chat("c1"), now)
            assertEquals(ListenableWorker.Result.success(), backfill().doWork())
            assertEquals(0, connector.historyCalls.get())
            assertNull(messages.oldest(accountId.chat("c1")))
        }

    @Test
    fun backfillStopsWhenAFullPageKeepsNothing() =
        runTest {
            seed()
            connector.history = (1..120).map { messageSnapshot("h$it", sentAt = now - it.minutes) }
            // The store refuses the page once, as it does for a chat hidden mid-run.
            connector.beforeHistory = { chats.hide(accountId.chat("c1"), now) }
            assertEquals(ListenableWorker.Result.success(), backfill().doWork())
            assertEquals(1, connector.historyCalls.get())
        }

    private fun backfill() =
        TestListenableWorkerBuilder<HistoryBackfillWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(workDataOf(HistoryBackfillWorker.KEY_CHAT to accountId.chat("c1").value))
            .build()

    @Test
    fun mediaDownloadRecordsWhereTheFileWent() =
        runTest {
            seed()
            val file = temp.newFile("photo.jpg")
            val attachment =
                Attachment(
                    accountId.attachment("a1"),
                    AttachmentKind.IMAGE,
                    "image/jpeg",
                    "photo.jpg",
                    10,
                    null,
                    "r",
                    null,
                    1,
                    1,
                    false,
                    null,
                )
            messages.upsert(messageSnapshot("m").message.copy(attachments = listOf(attachment)))
            connector.download = { file }
            val worker =
                TestListenableWorkerBuilder<MediaDownloadWorker>(context)
                    .setWorkerFactory(factory)
                    .setInputData(workDataOf(MediaDownloadWorker.KEY_ATTACHMENT to attachment.id.value))
                    .build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(file.absolutePath, messages.attachment(attachment.id)?.localPath)
        }

    private fun picture(id: String) =
        Attachment(
            accountId.attachment(id),
            AttachmentKind.IMAGE,
            "image/jpeg",
            "$id.jpg",
            10,
            null,
            "r",
            null,
            1,
            1,
            false,
            null,
        )

    private fun fetcher() = MediaFetcher(context, registry, accounts, messages, keeper, router, scope)

    @Test
    fun aReceivedPictureIsFetchedAtOnceNotQueued() =
        runTest {
            // Pictures waited in the job queue for minutes, or never came (owner, 2026-11-09).
            seed()
            val file = temp.newFile("now.jpg")
            val attachment = picture("now")
            messages.upsert(messageSnapshot("m").message.copy(attachments = listOf(attachment)))
            connector.download = { file }
            fetcher().now(attachment.id, "test")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    while (messages.attachment(attachment.id)?.localPath == null) kotlinx.coroutines.delay(20)
                }
            }
            assertEquals(file.absolutePath, messages.attachment(attachment.id)?.localPath)
        }

    @Test
    fun aHeldPreviewIsReplacedByTheWholePicture() =
        runTest {
            // A 70 KB preview of a 680 KB picture stayed in the chat, unreadable (owner, 2026-11-11).
            seed()
            val preview = temp.newFile("preview.jpg").apply { writeBytes(ByteArray(70)) }
            val whole = temp.newFile("whole.jpg").apply { writeBytes(ByteArray(680)) }
            val attachment = picture("doc").copy(sizeBytes = 680, localPath = preview.absolutePath)
            messages.upsert(messageSnapshot("m").message.copy(attachments = listOf(attachment)))
            var asked: Attachment? = null
            connector.download = {
                asked = it
                whole
            }
            assertEquals(MediaFetcher.Outcome.DONE, fetcher().fetch(attachment.id, attempt = 0))
            assertEquals(null, asked?.localPath)
            assertEquals(whole.absolutePath, messages.attachment(attachment.id)?.localPath)
        }

    @Test
    fun aWholePictureIsNotFetchedAgainAndAFailedUpgradeKeepsThePreview() =
        runTest {
            seed()
            val whole = temp.newFile("kept.jpg").apply { writeBytes(ByteArray(680)) }
            val held = picture("kept").copy(sizeBytes = 680, localPath = whole.absolutePath)
            val preview = temp.newFile("small.jpg").apply { writeBytes(ByteArray(70)) }
            val small = picture("small").copy(sizeBytes = 680, localPath = preview.absolutePath)
            messages.upsert(messageSnapshot("m").message.copy(attachments = listOf(held, small)))
            connector.download = { throw java.io.IOException("network gone") }
            assertEquals(MediaFetcher.Outcome.DONE, fetcher().fetch(held.id, attempt = 0))
            assertEquals(MediaFetcher.Outcome.RETRY, fetcher().fetch(small.id, attempt = 0))
            assertEquals(preview.absolutePath, messages.attachment(small.id)?.localPath)
        }

    @Test
    fun aFailedFetchIsTriedAgainThenStopsAtTheLastTry() =
        runTest {
            seed()
            val attachment = picture("bad")
            messages.upsert(messageSnapshot("m").message.copy(attachments = listOf(attachment)))
            connector.download = { throw java.io.IOException("network gone") }
            assertEquals(MediaFetcher.Outcome.RETRY, fetcher().fetch(attachment.id, attempt = 0))
            assertEquals(
                MediaFetcher.Outcome.GIVE_UP,
                fetcher().fetch(attachment.id, attempt = MediaFetcher.MAX_ATTEMPTS - 1),
            )
        }

    @Test
    fun dueScheduledMessagesAreSentAndReplaceTheirPendingBubble() =
        runTest {
            seed()
            val pending =
                messageSnapshot(
                    "pending",
                    outgoing = true,
                ).message.copy(status = MessageStatus.Scheduled(now))
            messages.upsert(pending)
            val draft = OutgoingMessage(pending.id, "see you", emptyList(), null, null, forceSms = false)
            scheduled.upsert(
                ScheduledSend(
                    pending.id,
                    now - 1.minutes,
                    accountId,
                    accountId.chat("c1"),
                    Json.encodeToString(OutgoingMessage.serializer(), draft),
                    0,
                ),
            )
            connector.sendResult = { SendResult.Sent(messageSnapshot("net-1", body = it.body!!, outgoing = true)) }
            val worker = TestListenableWorkerBuilder<ScheduledSendWorker>(context).setWorkerFactory(factory).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertNull(messages.get(pending.id))
            assertEquals("see you", messages.get(accountId.message("net-1"))?.body)
            assertEquals(emptyList<ScheduledSend>(), scheduled.due(now))
        }

    /**
     * The exact alarm and the fallback job wake together at the message's time; the message goes
     * once (owner, 2026-11-05: a message scheduled for 6:54 went to Parker twice).
     */
    @Test
    fun twoWakeUpsAtOnceSendTheMessageOnce() =
        runTest {
            seed()
            val pending =
                messageSnapshot("pending", outgoing = true).message.copy(status = MessageStatus.Scheduled(now))
            messages.upsert(pending)
            val draft = OutgoingMessage(pending.id, "have a good day", emptyList(), null, null, forceSms = false)
            scheduled.upsert(
                ScheduledSend(
                    pending.id,
                    now - 1.minutes,
                    accountId,
                    accountId.chat("c1"),
                    Json.encodeToString(OutgoingMessage.serializer(), draft),
                    0,
                ),
            )
            val sends =
                java.util.concurrent.atomic
                    .AtomicInteger()
            connector.sendResult = {
                val n = sends.incrementAndGet()
                // As Google Messages does, the send waits a while for the phone's copy.
                Thread.sleep(SLOW_SEND_MS)
                SendResult.Sent(messageSnapshot("net-$n", body = it.body!!, outgoing = true))
            }
            val workers =
                List(2) { TestListenableWorkerBuilder<ScheduledSendWorker>(context).setWorkerFactory(factory).build() }
            workers
                .map { w -> async(Dispatchers.Default) { w.doWork() } }
                .forEach { it.await() }
            assertEquals(1, sends.get())
            assertEquals(emptyList<ScheduledSend>(), scheduled.due(now))
        }

    /**
     * Two messages set for the same moment ("Last used") go in the order they were scheduled, the
     * second right after the first (owner, 2026-11-07).
     */
    @Test
    fun messagesSetForTheSameMomentGoInTheOrderTheyWereScheduled() =
        runTest {
            seed()
            // Scheduled first, though its id sorts last: the order must come from when, not what.
            listOf("zz-first" to "first", "aa-second" to "second").forEach { (id, body) ->
                val pending =
                    messageSnapshot(id, outgoing = true).message.copy(status = MessageStatus.Scheduled(now))
                messages.upsert(pending)
                val draft = OutgoingMessage(pending.id, body, emptyList(), null, null, forceSms = false)
                scheduled.upsert(
                    ScheduledSend(
                        pending.id,
                        now - 1.minutes,
                        accountId,
                        accountId.chat("c1"),
                        Json.encodeToString(OutgoingMessage.serializer(), draft),
                        0,
                    ),
                )
            }
            val order = mutableListOf<String>()
            connector.sendResult = {
                order += it.body!!
                SendResult.Sent(messageSnapshot("net-${order.size}", body = it.body!!, outgoing = true))
            }
            TestListenableWorkerBuilder<ScheduledSendWorker>(context).setWorkerFactory(factory).build().doWork()
            assertEquals(listOf("first", "second"), order)
        }

    @Test
    fun aSendThatGoesWellAfterItsTimeSaysSo() =
        runTest {
            seed()
            org.robolectric.Shadows
                .shadowOf(context.applicationContext as android.app.Application)
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            val pending =
                messageSnapshot("pending", outgoing = true)
                    .message
                    .copy(status = MessageStatus.Scheduled(now - 20.minutes))
            messages.upsert(pending)
            val draft = OutgoingMessage(pending.id, "running late", emptyList(), null, null, forceSms = false)
            scheduled.upsert(
                ScheduledSend(
                    pending.id,
                    now - 20.minutes,
                    accountId,
                    accountId.chat("c1"),
                    Json.encodeToString(OutgoingMessage.serializer(), draft),
                    0,
                ),
            )
            connector.sendResult = { SendResult.Sent(messageSnapshot("net-1", body = it.body!!, outgoing = true)) }
            val worker = TestListenableWorkerBuilder<ScheduledSendWorker>(context).setWorkerFactory(factory).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            val late =
                org.robolectric.Shadows
                    .shadowOf(manager)
                    .allNotifications
                    .single()
            assertEquals("Sent late to Sam Ortiz", late.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals("running late", late.extras.getString(android.app.Notification.EXTRA_TEXT))
        }

    @Test
    fun aScheduledSendThatCannotGoIsMarkedFailed() =
        runTest {
            seed()
            val pending =
                messageSnapshot(
                    "pending",
                    outgoing = true,
                ).message.copy(status = MessageStatus.Scheduled(now))
            messages.upsert(pending)
            val draft = OutgoingMessage(pending.id, "x", emptyList(), null, null, forceSms = false)
            scheduled.upsert(
                ScheduledSend(
                    pending.id,
                    now,
                    accountId,
                    accountId.chat("c1"),
                    Json.encodeToString(OutgoingMessage.serializer(), draft),
                    0,
                ),
            )
            connector.sendResult = { SendResult.Failed("Blocked", retryable = false) }
            val worker = TestListenableWorkerBuilder<ScheduledSendWorker>(context).setWorkerFactory(factory).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(MessageStatus.Failed("Blocked"), messages.get(pending.id)?.status)
        }
}

private const val SLOW_SEND_MS = 400L
