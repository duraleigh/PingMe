// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.people

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.pingme.core.model.PersonId
import org.pingme.core.service.ApplicationScope
import org.pingme.core.store.ContactRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A network's profile photos (Instagram, Messenger), fetched once into app storage so they
 * show without a fetch each time and outlive the short-lived links the networks hand out
 * (UI_DESIGN.md 10.18: the network's photo is the fallback after the contact's, and a
 * choice in Chat details). The only address ever fetched is the one the network gave.
 */
@Singleton
class ProfilePhotos
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contacts: ContactRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val dir by lazy { File(context.filesDir, "avatars").apply { mkdirs() } }
        private val fetching = ConcurrentHashMap.newKeySet<String>()

        // A few at a time: a first sync lists hundreds of people at once, and hundreds of
        // fetches in one go time out and leave photos missing at random (owner, Phase 7).
        private val lane = kotlinx.coroutines.sync.Semaphore(AT_ONCE)

        /** True for a link a network gave, as opposed to a file already on the phone. */
        fun isRemote(path: String?): Boolean =
            path != null && (path.startsWith("https://") || path.startsWith("http://"))

        /** The saved copy of [url], or null when it has not been fetched yet. */
        fun cached(url: String): String? = fileFor(url).takeIf { it.length() > 0 }?.absolutePath

        /** Fetches [url] for [person] in the background and records the file against them. */
        fun fetchSoon(
            person: PersonId,
            url: String,
        ) {
            val file = fileFor(url)
            if (file.length() > 0 || !fetching.add(file.name)) return
            scope.launch {
                try {
                    lane.withPermit { download(url, file) }
                    contacts.setAvatar(person, file.absolutePath)
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Could not fetch a profile photo for ${person.value}: ${e.message}")
                } finally {
                    fetching.remove(file.name)
                }
            }
        }

        private suspend fun download(
            url: String,
            file: File,
        ) = withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("profile photo: HTTP ${connection.responseCode}")
                }
                val temp = File(dir, "${file.name}.part")
                connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                if (!temp.renameTo(file)) throw java.io.IOException("could not keep the profile photo")
            } finally {
                connection.disconnect()
            }
        }

        // Keyed by the link without its signature, which the networks change on every listing.
        private fun fileFor(url: String): File {
            val stable = url.substringBefore('?')
            val digest = MessageDigest.getInstance("SHA-1").digest(stable.toByteArray())
            return File(dir, digest.joinToString("") { "%02x".format(it) })
        }

        private companion object {
            const val TAG = "PingMePhotos"
            const val TIMEOUT_MS = 20_000
            const val AT_ONCE = 4
        }
    }
