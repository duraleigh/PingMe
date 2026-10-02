// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.accountId
import org.pingme.core.model.AttachmentId
import org.pingme.core.model.ChatId
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.ScheduledSend
import org.pingme.core.service.EventApplier
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.ScheduledSendRepository
import kotlin.time.Clock

// Deferred work runs through WorkManager, not the service (DESIGN.md 6.4, BUILD_PLAN.md P1.4).

/** Fetches older messages for one chat, a few pages per run, continuing where the store ends. */
@HiltWorker
class HistoryBackfillWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val registry: ConnectorRegistry,
        private val accounts: AccountRepository,
        private val messages: MessageRepository,
        private val applier: EventApplier,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val chatId = inputData.getString(KEY_CHAT)?.let(::ChatId) ?: return Result.failure()
            val account = accounts.get(chatId.accountId) ?: return Result.failure()
            val connector = registry[account.network] ?: return Result.failure()
            repeat(PAGES_PER_RUN) {
                val page = connector.syncMessages(chatId, before = messages.oldest(chatId), limit = PAGE_SIZE)
                val complete = page.size < PAGE_SIZE
                applier.apply(ConnectorEvent.HistoryBatch(account.id, chatId, page, complete))
                if (complete) return Result.success()
            }
            // More history remains; the next run continues from the new oldest message.
            Work.backfill(applicationContext, chatId)
            return Result.success()
        }

        companion object {
            const val KEY_CHAT = "chat"
            const val PAGE_SIZE = 50
            const val PAGES_PER_RUN = 4
        }
    }

/** Downloads one attachment into app storage (DESIGN.md 6.3: media is fetched lazily). */
@HiltWorker
class MediaDownloadWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val registry: ConnectorRegistry,
        private val accounts: AccountRepository,
        private val messages: MessageRepository,
        private val keeper: org.pingme.core.service.MediaKeeper,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val id = inputData.getString(KEY_ATTACHMENT)?.let(::AttachmentId) ?: return Result.failure()
            val attachment = messages.attachment(id) ?: return Result.success()
            if (attachment.localPath != null) return Result.success()
            val account = accounts.get(id.accountId) ?: return Result.failure()
            val connector = registry[account.network] ?: return Result.failure()
            val started = System.currentTimeMillis()
            return runCatching { connector.downloadAttachment(attachment) }.fold(
                onSuccess = { file ->
                    messages.setAttachmentLocalPath(id, file.absolutePath)
                    keeper.downloaded(attachment, file)
                    // On the phone only (no telemetry, DESIGN.md 6.5): how long media takes to come in.
                    val took = System.currentTimeMillis() - started
                    android.util.Log.i(TAG, "downloaded ${attachment.kind} ${file.length()} bytes in $took ms")
                    Result.success()
                },
                onFailure = {
                    val took = System.currentTimeMillis() - started
                    android.util.Log.w(TAG, "download of ${attachment.kind} failed after $took ms", it)
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                },
            )
        }

        companion object {
            const val KEY_ATTACHMENT = "attachment"
            const val MAX_ATTEMPTS = 5
            private const val TAG = "PingMeMedia"
        }
    }

/**
 * Sends every scheduled message whose time has come, including late ones (UI_DESIGN.md
 * 10.13). [SendAlarm] wakes it, by exact alarm or by the fallback job; a message that goes
 * more than a few minutes after its time raises a "sent late" notice.
 */
@HiltWorker
class ScheduledSendWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val registry: ConnectorRegistry,
        private val accounts: AccountRepository,
        private val messages: MessageRepository,
        private val scheduled: ScheduledSendRepository,
        private val applier: EventApplier,
        private val clock: Clock,
        private val alarm: SendAlarm,
        private val notifications: org.pingme.core.service.NotificationRouter,
        private val previews: org.pingme.core.service.links.PreviewRequests,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val results = scheduled.due(clock.now()).map { send(it) }
            // Wake again for whichever message is due next.
            alarm.arm()
            return if (results.any { it == Outcome.RetryLater }) Result.retry() else Result.success()
        }

        private suspend fun send(send: ScheduledSend): Outcome {
            val account = accounts.get(send.accountId)
            val connector = account?.let { registry[it.network] }
            val draft = Json.decodeFromString(OutgoingMessage.serializer(), send.payloadJson)
            val result =
                when (connector) {
                    null -> {
                        SendResult.Failed("This account is no longer connected", retryable = false)
                    }

                    else -> {
                        runCatching { connector.send(send.chatId, draft) }
                            .getOrElse { SendResult.Failed(it.message ?: "Send failed", retryable = true) }
                    }
                }
            return when (result) {
                is SendResult.Sent -> {
                    // The pending bubble becomes the real message.
                    messages.delete(send.messageId)
                    applier.apply(ConnectorEvent.MessageUpdated(send.accountId, result.message))
                    scheduled.delete(send.messageId)
                    if (clock.now() - send.sendAt > LATE) notifications.sentLate(result.message.message)
                    previews.request(result.message.message)
                    Outcome.Done
                }

                is SendResult.Failed -> {
                    if (result.retryable && send.attempts + 1 < MAX_ATTEMPTS) {
                        scheduled.recordAttempt(send.messageId)
                        Outcome.RetryLater
                    } else {
                        messages.updateStatus(send.messageId, MessageStatus.Failed(result.reason))
                        scheduled.delete(send.messageId)
                        Outcome.Done
                    }
                }
            }
        }

        private enum class Outcome { Done, RetryLater }

        companion object {
            const val MAX_ATTEMPTS = 5

            /** Later than this after its time, a scheduled send is announced as late (UI_DESIGN.md 10.13). */
            val LATE = kotlin.time.Duration.parse("5m")
        }
    }

/**
 * Fetches a link preview on the phone when the network sent none (UI_DESIGN.md 10.12),
 * within the 1 MB cap and the "Generate link previews" setting (Always, Only on Wi-Fi, Never).
 */
@HiltWorker
class LinkPreviewWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val messages: MessageRepository,
        private val settings: org.pingme.core.store.SettingsRepository,
        private val previews: org.pingme.core.service.links.LinkPreviews,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val id = inputData.getString(KEY_MESSAGE)?.let { MessageId(it) } ?: return Result.failure()
            val message = messages.get(id) ?: return done("message gone")
            if (message.linkPreview != null) return done("already has one")
            val url =
                message.body?.let {
                    org.pingme.core.service.links.CleanLinks.LINK
                        .find(it)
                        ?.value
                }
                    ?: return done("no link")
            val mode =
                settings.app
                    .first()
                    .privacy.linkPreviews
            if (!previews.allowed(mode)) return done("not allowed now ($mode)")
            val preview = previews.fetch(url) ?: return done("nothing to show for $url")
            // Re-read: the message may have changed while the page loaded.
            messages.get(id)?.let { messages.upsert(it.copy(linkPreview = preview)) }
            return done("stored: ${preview.title}")
        }

        // On the phone only (no telemetry, DESIGN.md 6.5): why a preview did or did not appear.
        private fun done(why: String): Result {
            android.util.Log.i(TAG, "preview: $why")
            return Result.success()
        }

        companion object {
            const val KEY_MESSAGE = "message"
            private const val TAG = "PingMeLinks"
        }
    }

/**
 * Matches people to phone contacts and caches their photos (UI_DESIGN.md 10.18). The plan
 * builds the matching in Phase 7; until then the worker is registered but has nothing to do.
 */
@HiltWorker
class ContactSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.success()
    }

/** Enqueues the workers, one unique job per chat or attachment. */
object Work {
    fun backfill(
        context: Context,
        chatId: ChatId,
    ) = enqueue<HistoryBackfillWorker>(
        context,
        "backfill:${chatId.value}",
        HistoryBackfillWorker.KEY_CHAT to chatId.value,
    )

    fun downloadMedia(
        context: Context,
        attachmentId: AttachmentId,
    ) = enqueue<MediaDownloadWorker>(
        context,
        "media:${attachmentId.value}",
        MediaDownloadWorker.KEY_ATTACHMENT to attachmentId.value,
    )

    fun sendScheduled(context: Context) = enqueue<ScheduledSendWorker>(context, "scheduled-send")

    fun linkPreview(
        context: Context,
        messageId: org.pingme.core.model.MessageId,
    ) = enqueue<LinkPreviewWorker>(
        context,
        "preview:${messageId.value}",
        LinkPreviewWorker.KEY_MESSAGE to messageId.value,
    )

    /**
     * Wakes the scheduled sender after [delay], replacing any earlier wake-up, so it runs when
     * the next scheduled message is due. The exact alarm replaces this in P4.2.
     */
    fun sendScheduledAfter(
        context: Context,
        delay: kotlin.time.Duration,
    ) {
        val request =
            androidx.work
                .OneTimeWorkRequestBuilder<ScheduledSendWorker>()
                .setInitialDelay(delay.inWholeMilliseconds.coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)
                .setConstraints(
                    androidx.work.Constraints
                        .Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                ).build()
        androidx.work.WorkManager
            .getInstance(context)
            .enqueueUniqueWork("scheduled-send-next", androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    private inline fun <reified W : CoroutineWorker> enqueue(
        context: Context,
        name: String,
        vararg data: Pair<String, String>,
    ) {
        val request =
            androidx.work
                .OneTimeWorkRequestBuilder<W>()
                .setInputData(workDataOf(*data))
                .setConstraints(
                    androidx.work.Constraints
                        .Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                ).build()
        androidx.work.WorkManager
            .getInstance(context)
            .enqueueUniqueWork(name, androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
