// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The passphrase does not open this file: wrong passphrase, or the file is damaged. */
class WrongPassphraseException : IOException("The passphrase does not open this backup")

/**
 * A backup file's lock (BUILD_PLAN.md Phase 8): AES-256-GCM under a key drawn from the
 * passphrase with PBKDF2, in chunks of a megabyte so a backup of any size streams through
 * without sitting in memory, each chunk sealed on its own and the file ended with an
 * empty chunk so a cut-off file is caught. Layout: magic, salt, nonce, then chunks of
 * [length][sealed bytes].
 */
object BackupCrypto {
    val MAGIC: ByteArray = "PINGME-BACKUP-1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 8
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val ROUNDS = 120_000
    private const val CHUNK = 1 shl 20
    private val random = SecureRandom()

    /** Wraps [out]: everything written is sealed with [passphrase]; close it to finish the file. */
    fun seal(
        out: OutputStream,
        passphrase: CharArray,
    ): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = key(passphrase, salt)
        out.write(MAGIC)
        out.write(salt)
        out.write(nonce)
        return Sealing(DataOutputStream(out), key, nonce)
    }

    /** Opens a file made by [seal]; the magic must already have been read off [input]. */
    fun open(
        input: InputStream,
        passphrase: CharArray,
    ): InputStream {
        val data = DataInputStream(input)
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val nonce = ByteArray(NONCE_BYTES).also(data::readFully)
        return Opening(data, key(passphrase, salt), nonce)
    }

    private fun key(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ROUNDS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun cipher(
        mode: Int,
        key: SecretKeySpec,
        nonce: ByteArray,
        index: Int,
    ): Cipher {
        val iv =
            ByteBuffer
                .allocate(NONCE_BYTES + Int.SIZE_BYTES)
                .put(nonce)
                .putInt(index)
                .array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(TAG_BITS, iv)) }
    }

    private class Sealing(
        private val out: DataOutputStream,
        private val key: SecretKeySpec,
        private val nonce: ByteArray,
    ) : FilterOutputStream(out) {
        private val buffer = ByteArray(CHUNK)
        private var filled = 0
        private var index = 0

        override fun write(b: Int) {
            buffer[filled++] = b.toByte()
            if (filled == CHUNK) flushChunk()
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            var at = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK - filled)
                System.arraycopy(b, at, buffer, filled, n)
                filled += n
                at += n
                left -= n
                if (filled == CHUNK) flushChunk()
            }
        }

        private fun flushChunk() {
            if (filled == 0) return
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, nonce, index++).doFinal(buffer, 0, filled)
            out.writeInt(sealed.size)
            out.write(sealed)
            filled = 0
        }

        override fun close() {
            flushChunk()
            // The end mark: an empty sealed chunk, so a file cut short is caught on reading.
            val end = cipher(Cipher.ENCRYPT_MODE, key, nonce, index++).doFinal(ByteArray(0))
            out.writeInt(end.size)
            out.write(end)
            super.close()
        }
    }

    private class Opening(
        private val data: DataInputStream,
        private val key: SecretKeySpec,
        private val nonce: ByteArray,
    ) : InputStream() {
        private var chunk = ByteArray(0)
        private var at = 0
        private var index = 0
        private var ended = false

        override fun read(): Int {
            if (!fill()) return -1
            return chunk[at++].toInt() and BYTE_MASK
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, chunk.size - at)
            System.arraycopy(chunk, at, b, off, n)
            at += n
            return n
        }

        /** True when there are bytes to hand out; reads and unseals the next chunk when needed. */
        private fun fill(): Boolean {
            while (at >= chunk.size) {
                if (ended) return false
                chunk = unsealNext()
                at = 0
                if (chunk.isEmpty()) ended = true
            }
            return true
        }

        /** The next chunk's bytes; a cut-off or damaged file and a wrong passphrase each say so. */
        private fun unsealNext(): ByteArray {
            val size = readSize()
            if (size < 0 || size > CHUNK + TAG_BITS) {
                throw IOException(if (size < 0) "The backup is cut short" else "The backup is damaged")
            }
            val sealed = ByteArray(size).also(data::readFully)
            return try {
                cipher(Cipher.DECRYPT_MODE, key, nonce, index++).doFinal(sealed)
            } catch (_: AEADBadTagException) {
                throw WrongPassphraseException()
            }
        }

        /** The next chunk's length, or -1 when the file ends before one. */
        private fun readSize(): Int =
            try {
                data.readInt()
            } catch (_: EOFException) {
                -1
            }
    }

    private const val BYTE_MASK = 0xFF
}
