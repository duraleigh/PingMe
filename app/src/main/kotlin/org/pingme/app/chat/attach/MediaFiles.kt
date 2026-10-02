// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Reading and opening the files attachments live in (UI_DESIGN.md 5.8).

/** The full name in a vCard (its FN line). */
suspend fun vCardName(file: File): String? =
    withContext(Dispatchers.IO) {
        runCatching { file.readLines() }
            .getOrNull()
            ?.firstOrNull { it.startsWith("FN:") || it.startsWith("FN;") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

/** Latitude and longitude from a GeoJSON point. */
suspend fun readPoint(file: File): Pair<Double, Double>? =
    withContext(Dispatchers.IO) {
        val text = runCatching { file.readText() }.getOrNull() ?: return@withContext null
        val numbers = Regex("""\[\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)""").find(text)?.groupValues ?: return@withContext null
        val lng = numbers[1].toDoubleOrNull()
        val lat = numbers[2].toDoubleOrNull()
        if (lat == null || lng == null) null else lat to lng
    }

suspend fun firstFrame(path: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            MediaMetadataRetriever().use {
                it.setDataSource(path)
                it.frameAtTime?.asImageBitmap()
            }
        }.getOrNull()
    }

/** How long a video or audio file plays, in milliseconds, or null when unreadable. */
suspend fun videoDurationMs(path: String): Long? =
    withContext(Dispatchers.IO) {
        runCatching {
            MediaMetadataRetriever().use {
                it.setDataSource(path)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()
    }

/** Opens a file in whichever app on the phone shows that kind of file. */
fun openFile(
    context: Context,
    file: File,
    mime: String,
) {
    val uri =
        runCatching { FileProvider.getUriForFile(context, OutgoingFiles.authority(context), file) }.getOrNull()
            ?: return
    launch(
        context,
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

/** Starts [intent] through the chooser; nothing happens when no app can take it. */
fun launch(
    context: Context,
    intent: Intent,
) {
    try {
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the phone opens it; the bubble stays as it is.
    }
}
