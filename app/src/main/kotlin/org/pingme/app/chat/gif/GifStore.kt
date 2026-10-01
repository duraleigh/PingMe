// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.gif

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pingme.app.BuildConfig
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.model.AttachmentKind
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GIF files on the phone (UI_DESIGN.md 5.5): previews fetched for the picker, the one being
 * sent, and favourites, which stay on the phone and need no search to use.
 */
@Singleton
open class GifStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** The online provider, or null when this build has no key. */
        open val provider: GifProvider? = BuildConfig.GIPHY_API_KEY.takeIf { it.isNotBlank() }?.let(::GiphyProvider)

        private val previews get() = File(context.cacheDir, "gif-previews").apply { mkdirs() }
        private val favourites get() = File(context.filesDir, "gif-favourites").apply { mkdirs() }

        /** The picker's copy of a GIF's small preview. */
        open suspend fun preview(gif: Gif): File? = download(gif.preview.url, File(previews, "${safe(gif.id)}.gif"))

        /** A GIF ready to send: [small] picks the smaller size offered for MMS. */
        open suspend fun forSending(
            gif: Gif,
            small: Boolean = false,
        ): OutgoingAttachment? {
            val rendition = if (small) gif.small ?: gif.full else gif.full
            val file = outgoing("${safe(gif.id)}.gif")
            return download(rendition.url, file)?.let { attachment(it) }
        }

        /** A copy of a favourite to send, so removing the favourite later cannot pull it from a message. */
        suspend fun favouriteForSending(favourite: File): OutgoingAttachment? =
            withContext(Dispatchers.IO) {
                runCatching { favourite.copyTo(outgoing(favourite.name)) }.getOrNull()?.let { attachment(it) }
            }

        /** Every favourite, newest first. */
        suspend fun favourites(): List<File> =
            withContext(Dispatchers.IO) {
                favourites
                    .listFiles()
                    .orEmpty()
                    .filter { it.isFile }
                    .sortedByDescending { it.lastModified() }
            }

        /** The name a GIF's favourite file has, to tell which results are favourites. */
        fun fileName(gif: Gif) = "${safe(gif.id)}.gif"

        /** Saves a GIF as a favourite, or forgets it if it already is one. */
        suspend fun toggleFavourite(gif: Gif): Boolean {
            val file = File(favourites, "${safe(gif.id)}.gif")
            if (file.exists()) {
                withContext(Dispatchers.IO) { file.delete() }
                return false
            }
            return download(gif.full.url, file) != null
        }

        suspend fun removeFavourite(file: File) = withContext(Dispatchers.IO) { file.delete() }

        private fun outgoing(name: String) =
            File(File(context.filesDir, "outgoing/${UUID.randomUUID()}").apply { mkdirs() }, name)

        private fun attachment(file: File) =
            OutgoingAttachment(file.path, "image/gif", AttachmentKind.GIF, file.name, caption = null)

        // Downloads once; a file already there is used as it is.
        private suspend fun download(
            url: String,
            target: File,
        ): File? =
            withContext(Dispatchers.IO) {
                if (target.length() > 0) return@withContext target
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = TIMEOUT_MS
                        connection.readTimeout = TIMEOUT_MS
                        val part = File(target.path + ".part")
                        connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                        part.renameTo(target)
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
                target.takeIf { it.length() > 0 }
            }

        private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

        private companion object {
            const val TIMEOUT_MS = 15_000
        }
    }
