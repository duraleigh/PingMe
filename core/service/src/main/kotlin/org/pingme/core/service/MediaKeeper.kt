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
 * Incoming media. Pictures, GIFs, stickers, videos, and voice notes are fetched the moment
 * they arrive, so they are there when the chat opens (owner, Gate G2: a received picture
 * sat empty for far too long); other files wait until shown. "Save all incoming media"
 * (UI_DESIGN.md 10.16) fetches everything on arrival and copies each downloaded file once
 * to the folder the user chose; without a folder it stays in app storage.
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
        /**
         * A message arrived or changed: its pictures and the like download now; with the
         * setting on, everything does. A file the phone has not finished fetching itself (no
         * remote reference yet) waits for the update that brings one, with no retry in between
         * (owner, Gate G3: a picture took minutes to show).
         */
        suspend fun arrived(message: Message) {
            if (message.isOutgoing) return
            val everything =
                settings.app
                    .first()
                    .media.saveAllMedia
            message.attachments
                .filter { it.localPath == null && it.remoteRef != null && (everything || it.kind in ON_ARRIVAL) }
                .forEach { download(it.id) }
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

        private companion object {
            val ON_ARRIVAL =
                setOf(
                    org.pingme.core.model.AttachmentKind.IMAGE,
                    org.pingme.core.model.AttachmentKind.GIF,
                    org.pingme.core.model.AttachmentKind.STICKER,
                    org.pingme.core.model.AttachmentKind.VIDEO,
                    org.pingme.core.model.AttachmentKind.VOICE,
                    org.pingme.core.model.AttachmentKind.AUDIO,
                )
        }
    }
