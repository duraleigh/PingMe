// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject

/**
 * Speech to text through Groq's Whisper endpoint, with the owner's own key (owner, 2026-10-06:
 * the phone's built-in recogniser hears nothing, so dictation goes out to Groq). This is the
 * one network call PingMe makes beyond the messaging networks and the GIF provider, and it
 * happens only while the owner holds the dictation key with a key entered (DESIGN.md 6.5).
 */
open class GroqTranscriber
    @Inject
    constructor() {
        /** The words in [file], or an exception naming what Groq answered. */
        open suspend fun transcribe(
            file: File,
            key: String,
        ): String =
            withContext(Dispatchers.IO) {
                val boundary = "pingme-${UUID.randomUUID()}"
                val body = multipart(boundary, FIELDS, file.name, AUDIO_MIME, file.readBytes())
                val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer $key")
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                    val code = connection.responseCode
                    if (code !in SUCCESS) {
                        val why =
                            connection.errorStream
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                .orEmpty()
                        error("Groq answered $code ${why.take(ERROR_CHARS)}".trim())
                    }
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }
                        .trim()
                } finally {
                    connection.disconnect()
                }
            }

        companion object {
            const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
            const val MODEL = "whisper-large-v3-turbo"
            private const val AUDIO_MIME = "audio/mp4"
            private const val TIMEOUT_MS = 30_000
            private const val ERROR_CHARS = 200
            private val SUCCESS = 200..299
            private val FIELDS = mapOf("model" to MODEL, "response_format" to "text")

            /** A multipart/form-data body: the text [fields], then the file part. */
            internal fun multipart(
                boundary: String,
                fields: Map<String, String>,
                fileName: String,
                mime: String,
                bytes: ByteArray,
            ): ByteArray {
                val head = StringBuilder()
                fields.forEach { (name, value) ->
                    head.append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                head.append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
                head.append("Content-Type: $mime\r\n\r\n")
                return head.toString().toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray()
            }
        }
    }
