// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.model.AttachmentKind
import java.io.File
import java.nio.ByteBuffer
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

/**
 * Records voice notes to AAC in an MP4 file, the format every network takes (UI_DESIGN.md 5.6).
 * A recording can pause and go on: each stretch is its own file, so what is recorded so far can
 * be played back while paused, and the stretches are joined into one note at the end.
 */
@Singleton
open class VoiceRecorder
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private var recorder: MediaRecorder? = null
        private var folder: File? = null
        private val parts = mutableListOf<File>()
        private var compact = false
        private var partStartedAt = 0L
        private var recordedMs = 0L

        /**
         * Starts recording; false when the microphone could not be opened. [compact] records at a
         * lower quality so a note fits the carrier's MMS limit for longer.
         */
        open fun start(
            now: Long,
            compact: Boolean = false,
        ): Boolean {
            cancel()
            folder = File(context.filesDir, "outgoing/${UUID.randomUUID()}").apply { mkdirs() }
            this.compact = compact
            recordedMs = 0
            val started = beginPart(now)
            if (!started) cancel()
            return started
        }

        /** How loud the microphone is right now, from 0 to 1; 0 while paused. */
        open fun level(): Float =
            (runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) / LOUDEST).coerceIn(0f, 1f)

        /** Pauses, and returns what has been recorded so far as one file to play, or null if nothing usable. */
        open fun pause(now: Long): File? {
            endPart(now)
            val soFar = folder?.let { File(it, PREVIEW) } ?: return null
            return soFar.takeIf { join(parts, it) }
        }

        /** Goes on recording after [pause]; false when the microphone could not be opened again. */
        open fun resume(now: Long): Boolean = recorder != null || beginPart(now)

        /** Stops and keeps the recording; null when it was too short or failed. */
        open fun finish(now: Long): Recording? {
            endPart(now)
            val home = folder ?: return null
            val note = File(home, NOTE)
            val length = recordedMs
            val ok = length >= SHORTEST_MS && join(parts, note)
            parts.forEach { it.delete() }
            parts.clear()
            folder = null
            if (ok) File(home, PREVIEW).delete() else home.deleteRecursively()
            return if (ok) Recording(note, length) else null
        }

        /** Stops and throws the recording away. */
        open fun cancel() {
            stopQuietly()
            folder?.deleteRecursively()
            folder = null
            parts.clear()
            recordedMs = 0
        }

        private fun beginPart(now: Long): Boolean {
            val home = folder ?: return false
            val target = File(home, "part${parts.size}.m4a")
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
                parts += target
                partStartedAt = now
            }.onFailure {
                made.release()
                target.delete()
            }.isSuccess
        }

        // Closes the stretch being recorded; one that captured nothing is dropped.
        private fun endPart(now: Long) {
            if (recorder == null) return
            if (stopQuietly()) {
                recordedMs += now - partStartedAt
            } else {
                parts.removeLastOrNull()?.delete()
            }
        }

        private fun stopQuietly(): Boolean {
            val running = recorder ?: return false
            recorder = null
            // stop() throws when nothing was captured; that stretch is simply unusable.
            val ok = runCatching { running.stop() }.isSuccess
            running.release()
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
            private const val NOTE = "voice.m4a"
            private const val PREVIEW = "so-far.m4a"
        }
    }

/**
 * Joins recorded stretches into [out], one after another, without re-encoding: every stretch
 * comes from the same encoder settings, so their AAC samples are copied into one MP4 track with
 * the times moved on. False when it could not be done.
 */
internal fun join(
    parts: List<File>,
    out: File,
): Boolean {
    if (parts.isEmpty()) return false
    if (parts.size == 1) return runCatching { parts.single().copyTo(out, overwrite = true) }.isSuccess
    out.delete()
    return runCatching {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            var track = -1
            var offsetUs = 0L
            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER)
            val info = MediaCodec.BufferInfo()
            parts.forEach { part ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(part.path)
                    extractor.selectTrack(0)
                    if (track < 0) {
                        track = muxer.addTrack(extractor.getTrackFormat(0))
                        muxer.start()
                    }
                    var lastUs = 0L
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        lastUs = extractor.sampleTime
                        val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        info.set(0, size, offsetUs + lastUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buffer, info)
                        extractor.advance()
                    }
                    offsetUs += lastUs + AAC_FRAME_US
                } finally {
                    extractor.release()
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }.onFailure { out.delete() }.isSuccess
}

private const val SAMPLE_BUFFER = 256 * 1024

// One AAC frame (1024 samples at 44.1 kHz), the gap between the last sample of one stretch and the next.
private const val AAC_FRAME_US = 23_220L
