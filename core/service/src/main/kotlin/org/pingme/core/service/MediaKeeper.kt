// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentId
import org.pingme.core.model.Message
import org.pingme.core.service.work.Work
import org.pingme.core.store.MessageRepository
import org.pingme.core.store.SettingsRepository
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * "Save all incoming media" (UI_DESIGN.md 10.16). With it on, media is fetched as it arrives
 * instead of when it is first shown, and each downloaded file is copied once to the folder
 * the user chose; without a folder it stays in app storage.
 */
@Singleton
open class MediaKeeper
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settings: SettingsRepository,
        private val messages: MessageRepository,
        private val clock: Clock,
    ) {
        /** A message arrived; with the setting on, its media downloads now. */
        suspend fun arrived(message: Message) {
            if (message.isOutgoing ||
                !settings.app
                    .first()
                    .media.saveAllMedia
            ) {
                return
            }
            message.attachments.filter { it.localPath == null }.forEach { download(it.id) }
        }

        protected open fun download(id: AttachmentId) {
            Work.downloadMedia(context, id)
        }

        /** A download finished; with the setting on, the file is kept, in the chosen folder if any. */
        suspend fun downloaded(
            attachment: Attachment,
            file: File,
        ) {
            val media = settings.app.first().media
            if (!media.saveAllMedia || attachment.savedAt != null) return
            val folder = media.saveFolderUri
            val kept = folder == null || withContext(Dispatchers.IO) { copy(Uri.parse(folder), attachment, file) }
            if (kept) messages.setAttachmentSavedAt(attachment.id, clock.now())
        }

        // Writes a new file into the chosen folder; false when the folder is gone or refuses.
        private fun copy(
            tree: Uri,
            attachment: Attachment,
            file: File,
        ): Boolean =
            try {
                val parent =
                    DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        DocumentsContract.getTreeDocumentId(tree),
                    )
                val name = attachment.fileName ?: file.name
                val target =
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        parent,
                        attachment.mimeType,
                        name,
                    )
                target != null &&
                    context.contentResolver.openOutputStream(target)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        true
                    } == true
            } catch (_: IOException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: SecurityException) {
                false
            }
    }
