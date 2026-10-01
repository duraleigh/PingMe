// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.model.AttachmentKind
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns what the user picks into files PingMe owns (UI_DESIGN.md 5.8). Networks send from a
 * file path, and a picked item's permission ends with the screen, so each pick is copied first.
 */
@Singleton
open class OutgoingFiles
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val folder get() = File(context.filesDir, "outgoing").apply { mkdirs() }

        /** Copies a picked photo, video, or file; null when it cannot be read. */
        open suspend fun copy(uri: Uri): OutgoingAttachment? =
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val name = displayName(uri) ?: "file.${extension(mime)}"
                val target = newFile(name)
                runCatching {
                    resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                }.getOrNull() ?: return@withContext null
                OutgoingAttachment(target.path, mime, kindFor(mime), name, caption = null)
            }

        /** Where the camera writes a new photo, and the address the camera app writes it through. */
        fun photoTarget(): Pair<File, Uri> {
            val file = newFile("photo.jpg")
            return file to FileProvider.getUriForFile(context, authority(context), file)
        }

        /** A photo the camera app finished writing to [file]. */
        fun photo(file: File) =
            file.takeIf { it.length() > 0 }?.let {
                OutgoingAttachment(it.path, "image/jpeg", AttachmentKind.IMAGE, it.name, caption = null)
            }

        /** A shared place, kept as a GeoJSON point (RFC 7946) so every network can read it. */
        suspend fun location(
            latitude: Double,
            longitude: Double,
        ): OutgoingAttachment =
            withContext(Dispatchers.IO) {
                val file = newFile("location.geojson")
                file.writeText("""{"type":"Point","coordinates":[$longitude,$latitude]}""")
                OutgoingAttachment(file.path, GEO_JSON, AttachmentKind.LOCATION, file.name, caption = null)
            }

        /** A picked contact as a vCard file; null when the phone will not give it. */
        open suspend fun contact(uri: Uri): OutgoingAttachment? =
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val key =
                    resolver.query(uri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: return@withContext null
                val card = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, key)
                val file = newFile("${displayName(uri) ?: "contact"}.vcf")
                runCatching {
                    resolver.openInputStream(card)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                }.getOrNull() ?: return@withContext null
                OutgoingAttachment(file.path, "text/vcard", AttachmentKind.CONTACT, file.name, caption = null)
            }

        private fun displayName(uri: Uri): String? =
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()

        // Each file gets its own folder so two picks with the same name never collide.
        private fun newFile(name: String) =
            File(File(folder, UUID.randomUUID().toString()).apply { mkdirs() }, name.replace('/', '_'))

        private fun extension(mime: String) = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"

        companion object {
            const val GEO_JSON = "application/geo+json"

            /** The FileProvider that shares PingMe's files with the camera and viewer apps. */
            fun authority(context: Context) = "${context.packageName}.files"

            /** What a file is, from its type. */
            fun kindFor(mime: String) =
                when {
                    mime == "image/gif" -> AttachmentKind.GIF
                    mime.startsWith("image/") -> AttachmentKind.IMAGE
                    mime.startsWith("video/") -> AttachmentKind.VIDEO
                    mime.startsWith("audio/") -> AttachmentKind.AUDIO
                    mime == "text/vcard" || mime == "text/x-vcard" -> AttachmentKind.CONTACT
                    mime == GEO_JSON -> AttachmentKind.LOCATION
                    else -> AttachmentKind.FILE
                }
        }
    }
