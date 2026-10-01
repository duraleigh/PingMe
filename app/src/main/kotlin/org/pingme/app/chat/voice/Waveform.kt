// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * The bars a voice note's bubble shows (UI_DESIGN.md 5.6), read from the audio itself so
 * notes from every network get one. Kept in memory once read.
 */
object Waveform {
    const val BARS = 36
    private val cache = LruCache<String, FloatArray>(CACHED)

    /** [BARS] loudness values from 0 to 1; a flat line when the file cannot be decoded. */
    suspend fun of(path: String): FloatArray =
        cache.get(path) ?: withContext(Dispatchers.Default) {
            val bars = runCatching { decode(path) }.getOrNull()?.takeIf { it.any { level -> level > 0f } }
            (bars ?: FloatArray(BARS) { FLAT }).also { cache.put(path, it) }
        }

    /** Folds any number of loudness samples into [count] bars scaled so the loudest is 1. */
    fun fold(
        samples: FloatArray,
        count: Int = BARS,
    ): FloatArray {
        if (samples.isEmpty()) return FloatArray(count) { FLAT }
        val bars =
            FloatArray(count) { bar ->
                val from = bar * samples.size / count
                val to = max(from + 1, (bar + 1) * samples.size / count).coerceAtMost(samples.size)
                (from until to).maxOf { samples[it] }
            }
        val loudest = bars.max().takeIf { it > 0f } ?: return FloatArray(count) { FLAT }
        return FloatArray(count) { (bars[it] / loudest).coerceIn(FLAT, 1f) }
    }

    // Decodes the audio to 16-bit PCM and keeps the peak of each short window.
    private fun decode(path: String): FloatArray {
        val extractor = MediaExtractor().apply { setDataSource(path) }
        try {
            val track =
                (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            try {
                return fold(peaks(extractor, codec))
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun peaks(
        extractor: MediaExtractor,
        codec: MediaCodec,
    ): FloatArray {
        val out = ArrayList<Float>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var window = 0f
        var counted = 0
        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex >= 0) {
                val pcm = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (pcm.hasRemaining()) {
                    window = max(window, abs(pcm.get().toFloat()) / Short.MAX_VALUE)
                    if (++counted == WINDOW) {
                        out += window
                        window = 0f
                        counted = 0
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return out.toFloatArray()
    }

    private const val CACHED = 64
    private const val FLAT = 0.08f
    private const val WINDOW = 1024
    private const val TIMEOUT_US = 10_000L
}
