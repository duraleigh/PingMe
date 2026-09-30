// SPDX-License-Identifier: AGPL-3.0-or-later
// A byte-level PNG, GIF, and WAV writer: the numbers are the file formats' own fields.
@file:Suppress("MagicNumber")

package org.pingme.connectors.demo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.sin

/**
 * Real media files for demo attachments, generated in code so the demo never downloads
 * anything (CLAUDE.md rule 8): PNG pictures, an animated GIF, and a WAV voice note.
 */
internal object DemoMedia {
    /** A [width] x [height] picture: two colours split diagonally, so it reads as a photo tile. */
    fun png(
        width: Int,
        height: Int,
        first: Int,
        second: Int,
    ): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0) // filter: none
            for (x in 0 until width) {
                val argb = if (x * height + y * width < width * height) first else second
                raw.write(argb shr 16 and BYTE)
                raw.write(argb shr 8 and BYTE)
                raw.write(argb and BYTE)
            }
        }
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        val header =
            ByteBuffer
                .allocate(13)
                .putInt(width)
                .putInt(height)
                .put(8)
                .put(2)
                .put(0)
                .put(0)
                .put(0)
        out.writeChunk("IHDR", header.array())
        out.writeChunk("IDAT", deflate(raw.toByteArray()))
        out.writeChunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * A looping animated GIF, [size] x [size], one solid colour per frame. Pixels are
     * written as uncompressed LZW: a clear code before every two pixels keeps every code
     * three bits wide, which every decoder accepts.
     */
    fun gif(
        size: Int,
        frameColours: List<Int>,
        frameDelayCs: Int,
    ): ByteArray {
        require(frameColours.size in 1..PALETTE) { "One to four frames" }
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.writeShort(size)
        out.writeShort(size)
        out.write(0xF1) // global colour table, 4 entries
        out.write(0)
        out.write(0)
        for (i in 0 until PALETTE) {
            val c = frameColours.getOrElse(i) { 0 }
            out.write(c shr 16 and BYTE)
            out.write(c shr 8 and BYTE)
            out.write(c and BYTE)
        }
        // Loop forever (NETSCAPE2.0 application extension).
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0))
        frameColours.indices.forEach { index ->
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
            out.writeShort(frameDelayCs)
            out.write(0)
            out.write(0)
            out.write(0x2C)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(size)
            out.writeShort(size)
            out.write(0)
            out.write(LZW_MIN_CODE_SIZE)
            out.writeSubBlocks(uncompressedLzw(index, size * size))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    /** A voice-note-like WAV: [durationMs] of a gently wobbling tone, 8 kHz mono 16-bit. */
    fun wav(durationMs: Int): ByteArray {
        val samples = SAMPLE_RATE * durationMs / MILLIS
        val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            val loudness = 0.5 + 0.5 * sin(2 * PI * 3 * t)
            pcm.putShort((sin(2 * PI * TONE_HZ * t) * loudness * AMPLITUDE).toInt().toShort())
        }
        val header =
            ByteBuffer
                .allocate(WAV_HEADER)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray())
                .putInt(WAV_HEADER - 8 + samples * 2)
                .put("WAVEfmt ".toByteArray())
                .putInt(16)
                .putShort(1)
                .putShort(1)
                .putInt(SAMPLE_RATE)
                .putInt(SAMPLE_RATE * 2)
                .putShort(2)
                .putShort(16)
                .put("data".toByteArray())
                .putInt(samples * 2)
        return header.array() + pcm.array()
    }

    private fun uncompressedLzw(
        colourIndex: Int,
        pixels: Int,
    ): ByteArray {
        val bits = BitWriter()
        repeat((pixels + 1) / 2) { pair ->
            bits.write(CLEAR_CODE, CODE_BITS)
            bits.write(colourIndex, CODE_BITS)
            if (pair * 2 + 1 < pixels) bits.write(colourIndex, CODE_BITS)
        }
        bits.write(END_CODE, CODE_BITS)
        return bits.toByteArray()
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun write(
            value: Int,
            width: Int,
        ) {
            repeat(width) { bit ->
                current = current or ((value shr bit and 1) shl count)
                count++
                if (count == 8) {
                    out.write(current)
                    current = 0
                    count = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (count > 0) out.write(current)
            return out.toByteArray()
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater =
            Deflater().apply {
                setInput(data)
                finish()
            }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(
        type: String,
        data: ByteArray,
    ) {
        write(ByteBuffer.allocate(4).putInt(data.size).array())
        val typed = type.toByteArray() + data
        write(typed)
        write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(typed) }.value.toInt()).array())
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value and BYTE)
        write(value shr 8 and BYTE)
    }

    private fun ByteArrayOutputStream.writeSubBlocks(data: ByteArray) {
        data.toList().chunked(MAX_SUB_BLOCK).forEach { block ->
            write(block.size)
            write(block.toByteArray())
        }
        write(0)
    }

    private val PNG_SIGNATURE =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private const val BYTE = 0xFF
    private const val PALETTE = 4
    private const val LZW_MIN_CODE_SIZE = 2
    private const val CLEAR_CODE = 4
    private const val END_CODE = 5
    private const val CODE_BITS = 3
    private const val MAX_SUB_BLOCK = 255
    private const val BUFFER = 4096
    private const val SAMPLE_RATE = 8000
    private const val MILLIS = 1000
    private const val TONE_HZ = 220.0
    private const val AMPLITUDE = 8000
    private const val WAV_HEADER = 44
}
