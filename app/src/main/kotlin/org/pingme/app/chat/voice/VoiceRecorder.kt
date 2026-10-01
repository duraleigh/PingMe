// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.model.AttachmentKind
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A finished recording: the file and how long it runs. */
data class Recording(
    val file: File,
    val durationMs: Long,
)

/** The recording as a voice note to send. */
fun Recording.asAttachment() =
    OutgoingAttachment(file.path, "audio/mp4", AttachmentKind.VOICE, file.name, caption = null, durationMs = durationMs)

/** Records voice notes to AAC in an MP4 file, the format every network takes (UI_DESIGN.md 5.6). */
@Singleton
open class VoiceRecorder
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private var recorder: MediaRecorder? = null
        private var file: File? = null
        private var startedAt = 0L

        /**
         * Starts recording; false when the microphone could not be opened. [compact] records at a
         * lower quality so a note fits the carrier's MMS limit for longer.
         */
        open fun start(
            now: Long,
            compact: Boolean = false,
        ): Boolean {
            stopQuietly()
            val target = File(File(context.filesDir, "outgoing/${UUID.randomUUID()}").apply { mkdirs() }, "voice.m4a")
            val made = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else legacyRecorder()
            return runCatching {
                made.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    setAudioSamplingRate(SAMPLE_RATE)
                    setAudioEncodingBitRate(if (compact) COMPACT_BIT_RATE else BIT_RATE)
                    setOutputFile(target.path)
                    prepare()
                    start()
                }
                recorder = made
                file = target
                startedAt = now
            }.onFailure {
                made.release()
                target.delete()
            }.isSuccess
        }

        /** How loud the microphone is right now, from 0 to 1. */
        open fun level(): Float =
            (runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) / LOUDEST).coerceIn(0f, 1f)

        /** Stops and keeps the recording; null when it was too short or failed. */
        open fun finish(now: Long): Recording? {
            val made = file
            val ok = stopQuietly()
            val length = now - startedAt
            if (made == null || !ok || length < SHORTEST_MS) {
                made?.parentFile?.deleteRecursively()
                return null
            }
            return Recording(made, length)
        }

        /** Stops and throws the recording away. */
        open fun cancel() {
            val made = file
            stopQuietly()
            made?.parentFile?.deleteRecursively()
        }

        private fun stopQuietly(): Boolean {
            val running = recorder ?: return false
            recorder = null
            // stop() throws when nothing was captured; that recording is simply unusable.
            val ok = runCatching { running.stop() }.isSuccess
            running.release()
            if (!ok) file = null
            return ok
        }

        @Suppress("DEPRECATION")
        private fun legacyRecorder() = MediaRecorder()

        companion object {
            /** Shorter than this is a slip of the thumb, not a voice note. */
            const val SHORTEST_MS = 600L
            private const val SAMPLE_RATE = 44_100
            private const val BIT_RATE = 64_000
            private const val COMPACT_BIT_RATE = 24_000
            private const val LOUDEST = 16_000f
        }
    }
