// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

/** The encryption and file handling; the Android Keystore itself is replaced by a plain key. */
class KeystoreCredentialStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store by lazy { KeystoreCredentialStore(temp.root, { key }) }

    @Test
    fun savedCredentialsComeBackAndAreNotStoredInTheClear() =
        runTest {
            val secret = "pairing-key-material".toByteArray()
            store.save("gmessages/acc-1", secret)
            assertArrayEquals(secret, store.load("gmessages/acc-1"))
            val files = temp.root.listFiles().orEmpty()
            assertTrue(files.size == 1)
            assertFalse("The ref is not the file name", files[0].name.contains("gmessages"))
            assertFalse("The secret is encrypted", String(files[0].readBytes()).contains("pairing-key-material"))
        }

    @Test
    fun missingAndDeletedCredentialsAreNull() =
        runTest {
            assertNull(store.load("never-saved"))
            store.save("ref", byteArrayOf(1, 2, 3))
            store.delete("ref")
            assertNull(store.load("ref"))
        }

    @Test
    fun savingAgainReplaces() =
        runTest {
            store.save("ref", byteArrayOf(1))
            store.save("ref", byteArrayOf(2))
            assertArrayEquals(byteArrayOf(2), store.load("ref"))
        }

    @Test(expected = AEADBadTagException::class)
    fun aTamperedFileIsRejected() =
        runTest {
            store.save("ref", "secret".toByteArray())
            val file = temp.root.listFiles()!!.single()
            val bytes = file.readBytes()
            bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            store.load("ref")
        }
}
