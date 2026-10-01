// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
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
                        HistoryBackfillWorker(appContext, workerParameters, registry, accounts, messages, applier)
                    }

                    MediaDownloadWorker::class.java.name -> {
                        MediaDownloadWorker(appContext, workerParameters, registry, accounts, messages)
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
