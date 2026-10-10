// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

/** The lock on a backup file (Phase 8, P8.2). */
class BackupCryptoTest {
    private fun sealed(
        plain: ByteArray,
        passphrase: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.seal(out, passphrase.toCharArray()).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun opened(
        file: ByteArray,
        passphrase: String,
    ): ByteArray {
        val input = ByteArrayInputStream(file)
        input.skip(BackupCrypto.MAGIC.size.toLong())
        return BackupCrypto.open(input, passphrase.toCharArray()).readBytes()
    }

    @Test
    fun whatGoesInComesOutAcrossSeveralChunks() {
        val plain = Random(1).nextBytes(3 * (1 shl 20) + 12_345)
        val file = sealed(plain, "correct horse")
        assertTrue(file.copyOf(BackupCrypto.MAGIC.size).contentEquals(BackupCrypto.MAGIC))
        assertArrayEquals(plain, opened(file, "correct horse"))
    }

    @Test
    fun anEmptyBackupStillRoundTrips() {
        assertArrayEquals(ByteArray(0), opened(sealed(ByteArray(0), "p"), "p"))
    }

    @Test
    fun theWrongPassphraseIsToldApartFromDamage() {
        val file = sealed("hello".toByteArray(), "right")
        assertThrows(WrongPassphraseException::class.java) { opened(file, "wrong") }
        val cut = file.copyOf(file.size - 20)
        val e = assertThrows(IOException::class.java) { opened(cut, "right") }
        assertTrue(e !is WrongPassphraseException)
    }
}
