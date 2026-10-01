// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.resume

/** A voice note's words, or why there are none. */
sealed interface Transcript {
    data object Working : Transcript

    data class Text(
        val text: String,
    ) : Transcript

    /** This phone cannot recognise speech from a file on the device. */
    data object Unavailable : Transcript

    data object Failed : Transcript
}

/**
 * Writes out voice notes on the phone with Android's on-device speech recogniser
 * (UI_DESIGN.md 5.6). Feeding it a file needs Android 13. Each transcript is kept, so a
 * note is only written out once; nothing leaves the phone.
 */
open class VoiceTranscriber
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val dir = File(context.filesDir, "transcripts")

        protected open val available: Boolean
            get() = Build.VERSION.SDK_INT >= TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** The note's words, from the saved copy when there is one. */
        suspend fun transcript(
            key: String,
            file: File,
        ): Transcript {
            val saved = File(dir, name(key))
            val kept = withContext(Dispatchers.IO) { saved.takeIf { it.exists() }?.readText() }
            return when {
                kept != null -> Transcript.Text(kept)
                !available -> Transcript.Unavailable
                else -> recognise(file)?.takeIf { it.isNotBlank() }?.let { keep(saved, it) } ?: Transcript.Failed
            }
        }

        private suspend fun keep(
            saved: File,
            text: String,
        ): Transcript =
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                saved.writeText(text)
                Transcript.Text(text)
            }

        protected open suspend fun recognise(file: File): String? {
            if (Build.VERSION.SDK_INT < TIRAMISU) return null
            val pcm = withContext(Dispatchers.IO) { decode(file) } ?: return null
            return try {
                withContext(Dispatchers.Main) { listen(pcm) }
            } finally {
                pcm.file.delete()
            }
        }

        // The recogniser hears 16-bit PCM, so the note is decoded first.
        private fun decode(source: File): Pcm? =
            try {
                val extractor = MediaExtractor().apply { setDataSource(source.path) }
                val track =
                    (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: return null
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val out = File(context.cacheDir, "transcribe-${System.nanoTime()}.pcm")
                val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    out.outputStream().use { pump(extractor, codec, it) }
                    val decoded = codec.outputFormat
                    Pcm(
                        out,
                        decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    )
                } finally {
                    codec.release()
                    extractor.release()
                }
            } catch (_: IOException) {
                null
            } catch (_: IllegalStateException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun pump(
            extractor: MediaExtractor,
            codec: MediaCodec,
            out: java.io.OutputStream,
        ) {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val slot = codec.dequeueInputBuffer(WAIT_US)
                    if (slot >= 0) {
                        val size = extractor.readSampleData(requireNotNull(codec.getInputBuffer(slot)), 0)
                        if (size < 0) {
                            codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val slot = codec.dequeueOutputBuffer(info, WAIT_US)
                if (slot >= 0) {
                    val buffer = requireNotNull(codec.getOutputBuffer(slot))
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    out.write(bytes)
                    codec.releaseOutputBuffer(slot, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }

        // One segmented session over the whole note, so pauses do not cut it short.
        @RequiresApi(TIRAMISU)
        private suspend fun listen(pcm: Pcm): String? =
            suspendCancellableCoroutine { done ->
                val recogniser = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val audio = ParcelFileDescriptor.open(pcm.file, ParcelFileDescriptor.MODE_READ_ONLY)
                val heard = mutableListOf<String>()
                var closed = false

                fun finish(text: String?) {
                    if (closed) return
                    closed = true
                    recogniser.destroy()
                    audio.close()
                    if (done.isActive) done.resume(text)
                }
                recogniser.setRecognitionListener(
                    object : RecognitionListener {
                        override fun onSegmentResults(segment: Bundle) {
                            segment.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                                heard +=
                                    it
                            }
                        }

                        override fun onEndOfSegmentedSession() = finish(heard.joinToString(" "))

                        override fun onResults(results: Bundle) =
                            finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())

                        override fun onError(error: Int) = finish(heard.takeIf { it.isNotEmpty() }?.joinToString(" "))

                        override fun onReadyForSpeech(params: Bundle?) = Unit

                        override fun onBeginningOfSpeech() = Unit

                        override fun onRmsChanged(rmsdB: Float) = Unit

                        override fun onBufferReceived(buffer: ByteArray?) = Unit

                        override fun onEndOfSpeech() = Unit

                        override fun onPartialResults(partialResults: Bundle?) = Unit

                        override fun onEvent(
                            eventType: Int,
                            params: Bundle?,
                        ) = Unit
                    },
                )
                recogniser.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.rate)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcm.channels)
                        .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE),
                )
                // The recogniser lives on the main thread, so it is closed there too.
                done.invokeOnCancellation { Handler(Looper.getMainLooper()).post { finish(null) } }
            }

        private fun name(key: String) =
            MessageDigest
                .getInstance("SHA-256")
                .digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) } + ".txt"

        private class Pcm(
            val file: File,
            val rate: Int,
            val channels: Int,
        )

        private companion object {
            const val TIRAMISU = 33
            const val WAIT_US = 10_000L
        }
    }
