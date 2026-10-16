// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi
import org.pingme.connectors.telegram.td.TelegramClient
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.model.AccountId
import java.io.File

/**
 * Downloads each user's small profile photo once and reports the person again with it
 * (owner, 2026-10-05: Telegram chats showed no pictures). Telegram keeps the file in its
 * own store; PingMe copies it beside the account's other files under `avatars/`.
 */
internal class TelegramPhotos(
    private val accountId: AccountId,
    private val client: TelegramClient,
    private val dir: File,
    private val go: TelegramTranslate,
    private val report: (ConnectorEvent) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Semaphore(PHOTO_PARALLEL)

    fun close() = scope.cancel()

    /** Fetches [userId]'s photo in the background unless it is already here or they have none. */
    fun soon(userId: Long) {
        val fileId = go.photoFileId(userId)
        if (fileId == 0 || go.hasAvatar(userId) || userId == go.ownId) return
        scope.launch {
            lock.withPermit {
                val target = File(File(dir, "avatars"), "$userId.jpg")
                if (!target.exists() && !fetch(fileId, target)) return@withPermit
                go.learnAvatar(userId, target.path)
                report(ConnectorEvent.PeopleUpdated(accountId, go.people(listOf(userId))))
            }
        }
    }

    private suspend fun fetch(
        fileId: Int,
        target: File,
    ): Boolean =
        try {
            val file = client.send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0, 0, true))
            val local = file.local
            if (local == null || !local.isDownloadingCompleted || local.path.isNullOrEmpty()) return false
            target.parentFile?.mkdirs()
            File(local.path).copyTo(target, overwrite = true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.i(TAG, "No profile photo for ${target.name}: ${e.message}")
            false
        }

    private companion object {
        const val TAG = "TelegramPhotos"
        const val PHOTO_PARALLEL = 2
        const val DOWNLOAD_PRIORITY = 16
    }
}
