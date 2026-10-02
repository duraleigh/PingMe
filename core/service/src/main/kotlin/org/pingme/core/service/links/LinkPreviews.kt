// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.links

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pingme.core.model.LinkPreview
import org.pingme.core.model.LinkPreviewMode
import org.pingme.core.model.LinkPreviewSource
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.time.Clock

/**
 * Fetches a link's preview on the phone when the network sent none (UI_DESIGN.md 10.12,
 * BUILD_PLAN.md P4.3): at most 1 MB of the page, the picture at most 2 MB, only as
 * "Generate link previews" allows (Always, Only on Wi-Fi, Never). Fetching tells the site
 * the phone's address, which is why the setting exists.
 */
class LinkPreviews(
    private val context: Context,
    private val links: CleanLinks,
    private val clock: Clock,
) {
    /** Whether fetching is allowed right now under [mode]. */
    fun allowed(mode: LinkPreviewMode): Boolean =
        when (mode) {
            LinkPreviewMode.ALWAYS -> true
            LinkPreviewMode.NEVER -> false
            LinkPreviewMode.WIFI_ONLY -> unmetered()
        }

    /**
     * The preview for [url], or null when the page gives nothing or cannot be read. The
     * card's link is where the page really is, after any redirects (a share.google link
     * ends on the site it points to; owner, Gate G3), cleaned of tracking.
     */
    suspend fun fetch(url: String): LinkPreview? =
        withContext(Dispatchers.IO) {
            val target = links.clean(url).let { if (it.startsWith("http")) it else "https://$it" }
            val fetched = runCatching { read(target, PAGE_CAP) }.getOrElse { return@withContext failed(target, it) }
            val landed = links.clean(fetched.finalUrl)
            val page = OpenGraph.parse(String(fetched.bytes, Charsets.UTF_8), landed)
            if (page.isEmpty) {
                Log.i(TAG, "no title, description, or picture in ${fetched.bytes.size} bytes from $landed")
                return@withContext null
            }
            val image = page.image?.let { runCatching { keepImage(it) }.getOrNull() }
            LinkPreview(url, landed, page.title, page.description, image, clock.now(), LinkPreviewSource.LOCAL)
        }

    private fun failed(
        target: String,
        error: Throwable,
    ): LinkPreview? {
        Log.i(TAG, "no preview for $target: ${error.message}")
        return null
    }

    private class Fetched(
        val bytes: ByteArray,
        /** Where the content came from, after redirects. */
        val finalUrl: String,
    )

    // At most [cap] bytes, with short timeouts, following redirects itself (across http and
    // https too, which the platform does not) so the final address is known.
    private fun read(
        address: String,
        cap: Int,
    ): Fetched {
        var url = address
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,image/*;q=0.8,*/*;q=0.5")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in REDIRECTS && location != null) {
                    url = URL(URL(url), location).toString()
                    hops++
                    continue
                }
                if (code !in HTTP_OK) throw IOException("HTTP $code")
                return Fetched(connection.inputStream.use { it.readUpTo(cap) }, url)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("more than $MAX_REDIRECTS redirects")
    }

    // Reads at most [cap] bytes (readNBytes needs Android 13; the app runs on 10 and newer).
    private fun java.io.InputStream.readUpTo(cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var left = cap
        while (left > 0) {
            val read = read(buffer, 0, minOf(buffer.size, left))
            if (read < 0) break
            out.write(buffer, 0, read)
            left -= read
        }
        return out.toByteArray()
    }

    private fun keepImage(address: String): String? {
        val bytes = read(address, IMAGE_CAP).bytes
        if (bytes.isEmpty()) return null
        val dir = File(context.filesDir, "previews").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        val file = File(dir, name.take(FILE_NAME_LENGTH))
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun unmetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private companion object {
        const val TAG = "PingMeLinks"
        const val PAGE_CAP = 1_000_000
        const val IMAGE_CAP = 2_000_000
        const val TIMEOUT_MS = 10_000
        const val FILE_NAME_LENGTH = 32
        const val BUFFER = 16_384
        val HTTP_OK = 200..299
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5

        /**
         * What Chrome on Android sends. Sites that refuse unknown readers (the New York
         * Times answered 403; owner, Gate G3) serve the page and its preview tags to this.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}
