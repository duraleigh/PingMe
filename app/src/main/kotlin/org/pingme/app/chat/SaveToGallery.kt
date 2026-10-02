// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pingme.core.model.Attachment
import org.pingme.core.model.AttachmentKind
import java.io.File
import java.io.IOException

/**
 * Copies a picture or video into the phone's Photos under a "PingMe" album (owner, Gate G2:
 * the viewer's download button). Pictures and GIFs go to Pictures/PingMe, videos to
 * Movies/PingMe, through the media store, so no storage permission is needed.
 */
suspend fun saveToGallery(
    context: Context,
    attachment: Attachment,
): Boolean =
    withContext(Dispatchers.IO) {
        val source = attachment.localPath?.let(::File)?.takeIf { it.exists() } ?: return@withContext false
        val video = attachment.kind == AttachmentKind.VIDEO
        val collection =
            if (video) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, attachment.fileName ?: source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, attachment.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, albumPath(video))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val target = resolver.insert(collection, values) ?: return@withContext false
        try {
            resolver.openOutputStream(target)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("cannot write")
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (_: IOException) {
            resolver.delete(target, null, null)
            false
        }
    }

/** "Pictures/PingMe" or "Movies/PingMe": the album Photos shows them under. */
fun albumPath(video: Boolean): String =
    (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + File.separator + ALBUM

const val ALBUM = "PingMe"
