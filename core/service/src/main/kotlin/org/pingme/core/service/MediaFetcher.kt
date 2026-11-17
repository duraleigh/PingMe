// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.Diag
import org.pingme.core.connector.accountId
import org.pingme.core.model.AttachmentId
import org.pingme.core.model.holdsPreview
import org.pingme.core.model.isPreviewOnly
import org.pingme.core.service.work.Work
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.MessageRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a received picture, voice note, or file. A picture used to wait in Android's
 * background job queue with no priority, and a chat opened while an earlier try sat out its
 * retry gap queued behind it; pictures took minutes or never came (owner, 2026-11-09). [now]
 * fetches at once inside the running app and leaves only a failure to the job queue, which
 * tries again later. Every step is written to the diagnostic file.
 */
@Singleton
class MediaFetcher
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val registry: ConnectorRegistry,
        private val accounts: AccountRepository,
        private val messages: MessageRepository,
        private val keeper: MediaKeeper,
        private val notifications: NotificationRouter,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val fetching = ConcurrentHashMap.newKeySet<String>()

        /** What one try came to. */
        enum class Outcome { DONE, RETRY, GIVE_UP }

        /** Fetches [id] now; one already on its way is not started twice. [why] goes in the diary. */
        fun now(
            id: AttachmentId,
            why: String,
        ) {
            if (!fetching.add(id.value)) return
            Diag.note(TAG, "${id.value}: fetching now ($why)")
            scope.launch {
                try {
                    if (fetch(id, attempt = 0) == Outcome.RETRY) Work.downloadMedia(context, id)
                } finally {
                    fetching.remove(id.value)
                }
            }
        }

        /** One try; the job queue's retries call this too, with their [attempt] number. */
        suspend fun fetch(
            id: AttachmentId,
            attempt: Int,
        ): Outcome {
            val held = messages.attachment(id) ?: return Outcome.DONE
            // A preview stays shown until the whole file replaces it; any other file is final.
            val upgrade = held.holdsPreview
            if (held.localPath != null && !upgrade) return Outcome.DONE
            val attachment = held.copy(localPath = null)
            // The phone has not fetched it itself yet; the update that brings a reference asks again.
            if (attachment.remoteRef == null) {
                Diag.note(TAG, "${id.value}: not on the network's side yet; waits for its update")
                return Outcome.DONE
            }
            val account = accounts.get(id.accountId) ?: return Outcome.GIVE_UP
            val connector = registry[account.network] ?: return Outcome.GIVE_UP
            val started = System.currentTimeMillis()
            return runCatching { connector.downloadAttachment(attachment) }.fold(
                onSuccess = { file ->
                    messages.setAttachmentLocalPath(id, file.absolutePath)
                    keeper.downloaded(attachment, file)
                    // The preview already alerted; the whole picture only replaces it.
                    if (!upgrade) notifications.pictureArrived(attachment, file)
                    Diag.note(
                        TAG,
                        "${id.value}: ${attachment.kind} fetched in ${System.currentTimeMillis() - started} ms " +
                            (
                                if (upgrade) {
                                    "in place of a ${held.localPath?.let {
                                        File(
                                            it,
                                        ).length()
                                    }}-byte preview "
                                } else {
                                    ""
                                }
                            ) +
                            "(try ${attempt + 1}), ${file.length()} bytes, the network says ${attachment.sizeBytes}, " +
                            "preview only: ${attachment.isPreviewOnly}",
                    )
                    Outcome.DONE
                },
                onFailure = { e ->
                    val retry = attempt + 1 < MAX_ATTEMPTS
                    Diag.note(
                        TAG,
                        "${id.value}: ${attachment.kind} failed after ${System.currentTimeMillis() - started} ms " +
                            "(try ${attempt + 1}): ${e::class.simpleName}: ${e.message}; " +
                            (if (upgrade) "the preview stays shown; " else "") +
                            if (retry) "tries again" else "gives up until the chat is opened",
                    )
                    if (retry) Outcome.RETRY else Outcome.GIVE_UP
                },
            )
        }

        companion object {
            const val MAX_ATTEMPTS = 6
            private const val TAG = "PingMeMedia"
        }
    }
