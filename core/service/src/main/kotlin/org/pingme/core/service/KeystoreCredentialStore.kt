// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pingme.core.connector.CredentialStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the AES key credentials are encrypted with. */
fun interface CredentialKeyProvider {
    fun key(): SecretKey
}

/**
 * The AES-256 key lives in the Android Keystore and never leaves it (DESIGN.md 6.5), so
 * credentials cannot be read off the phone or restored onto another one.
 */
class AndroidKeystoreKeyProvider : CredentialKeyProvider {
    override fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pingme_credentials"
        const val KEY_BITS = 256
    }
}

/**
 * Connector credentials (pairing keys, session cookies), each in its own AES-GCM file in
 * no-backup storage (BUILD_PLAN.md P3.2, DESIGN.md 5.3). App updates, restarts, and crashes
 * never lose them; only the user removing the account does.
 */
@Singleton
class KeystoreCredentialStore(
    private val directory: File,
    private val keys: CredentialKeyProvider,
) : CredentialStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(File(context.noBackupFilesDir, "credentials"), AndroidKeystoreKeyProvider())

    override suspend fun save(
        ref: String,
        secret: ByteArray,
    ) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keys.key()) }
        val sealed = cipher.iv + cipher.doFinal(secret)
        // Write then rename, so a crash mid-write never leaves a half-written credential.
        val temp = File(directory, "${fileName(ref)}.tmp")
        temp.writeBytes(sealed)
        check(temp.renameTo(fileFor(ref))) { "Could not store credentials" }
    }

    override suspend fun load(ref: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val file = fileFor(ref)
            if (!file.exists()) return@withContext null
            val sealed = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }

    override suspend fun delete(ref: String) {
        withContext(Dispatchers.IO) { fileFor(ref).delete() }
    }

    private fun fileFor(ref: String) = File(directory, fileName(ref))

    /** File names are hashes, so a ref never has to be a safe file name. */
    private fun fileName(ref: String) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(ref.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
