// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The body Groq's Whisper endpoint is sent. */
class GroqTranscriberTest {
    @Test
    fun theBodyCarriesTheModelTheTextFormatAndTheFile() {
        val audio = byteArrayOf(1, 2, 3)
        val body =
            GroqTranscriber
                .multipart(
                    "b0",
                    mapOf("model" to GroqTranscriber.MODEL, "response_format" to "text"),
                    "note.m4a",
                    "audio/mp4",
                    audio,
                )
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(
            text.contains("--b0\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n${GroqTranscriber.MODEL}\r\n"),
        )
        assertTrue(text.contains("name=\"response_format\"\r\n\r\ntext\r\n"))
        assertTrue(
            text.contains(
                "name=\"file\"; filename=\"note.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n\u0001\u0002\u0003\r\n",
            ),
        )
        assertTrue(text.endsWith("--b0--\r\n"))
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", GroqTranscriber.ENDPOINT)
    }
}
