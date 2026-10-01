// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.gif

import android.content.Context
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.model.AttachmentKind
import java.io.File

/** GIF files for tests: [provider] answers from a script, and "downloads" write [bytes] of data. */
class FakeGifStore(
    context: Context,
    private val folder: File,
) : GifStore(context) {
    var gifs: List<Gif> = emptyList()
    var bytes = 4096
    var smallBytes = 1024
    val queries = mutableListOf<String>()

    override val provider: GifProvider =
        object : GifProvider {
            override val attribution = "Powered by GIPHY"

            override suspend fun trending(offset: Int): List<Gif> {
                queries += "trending"
                return if (offset == 0) gifs else emptyList()
            }

            override suspend fun search(
                query: String,
                offset: Int,
            ): List<Gif> {
                queries += query
                return if (offset == 0) gifs.filter { it.title.contains(query, ignoreCase = true) } else emptyList()
            }
        }

    override suspend fun preview(gif: Gif): File? = null

    override suspend fun forSending(
        gif: Gif,
        small: Boolean,
    ): OutgoingAttachment {
        val file =
            File
                .createTempFile(
                    gif.id,
                    ".gif",
                    folder,
                ).apply { writeBytes(ByteArray(if (small) smallBytes else bytes)) }
        return OutgoingAttachment(file.path, "image/gif", AttachmentKind.GIF, file.name, caption = null)
    }
}
