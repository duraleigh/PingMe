// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.gif

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GIF search from GIPHY (api.giphy.com/v1/gifs). Sends only the search words, the page, and
 * the rating; no user id, and none of GIPHY's optional analytics pings (CLAUDE.md rule 8).
 */
class GiphyProvider(
    private val key: String,
    private val fetch: suspend (String) -> String = ::get,
) : GifProvider {
    override val attribution = "Powered by GIPHY"

    override suspend fun trending(offset: Int) = parse(fetch(url("trending", offset)))

    override suspend fun search(
        query: String,
        offset: Int,
    ) = parse(fetch(url("search", offset) + "&q=" + URLEncoder.encode(query.take(MAX_QUERY), "UTF-8")))

    private fun url(
        endpoint: String,
        offset: Int,
    ) = "$BASE/$endpoint?api_key=$key&limit=$PAGE&offset=$offset&rating=$RATING"

    companion object {
        const val PAGE = 30
        private const val BASE = "https://api.giphy.com/v1/gifs"
        private const val RATING = "pg-13"
        private const val MAX_QUERY = 50
        private const val TIMEOUT_MS = 10_000
        private val SUCCESS = 200..299

        private val json = Json { ignoreUnknownKeys = true }

        /** GIFs from a GIPHY search or trending reply; ones missing a size we need are skipped. */
        fun parse(body: String): List<Gif> =
            (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray).orEmpty().mapNotNull { item ->
                val gif = item.jsonObject
                val images = gif["images"] as? JsonObject ?: return@mapNotNull null
                val preview = images.rendition("fixed_width_downsampled") ?: images.rendition("fixed_width")
                val full = images.rendition("downsized") ?: images.rendition("original")
                if (preview == null || full == null) return@mapNotNull null
                Gif(
                    id = gif["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    title = gif["title"]?.jsonPrimitive?.content.orEmpty(),
                    preview = preview,
                    full = full,
                    // The 200-pixel downsampled GIF is both smaller and sharper than GIPHY's "small" one.
                    small =
                        listOfNotNull(
                            images.rendition("fixed_width_downsampled"),
                            images.rendition("fixed_width_small"),
                        ).firstOrNull { it.bytes in 1 until full.bytes },
                )
            }

        private fun JsonObject.rendition(name: String): Rendition? {
            val image = get(name) as? JsonObject ?: return null
            val url = image["url"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") } ?: return null

            fun number(key: String) =
                image[key]?.jsonPrimitive?.content?.toLongOrNull() ?: image[key]?.jsonPrimitive?.longOrNull ?: 0L
            return Rendition(url, number("width").toInt(), number("height").toInt(), number("size"))
        }

        private fun JsonArray?.orEmpty() = this?.jsonArray ?: JsonArray(emptyList())

        /** A plain HTTPS GET; the body as text. */
        suspend fun get(address: String): String =
            withContext(Dispatchers.IO) {
                val connection = URL(address).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    if (connection.responseCode !in SUCCESS) error("GIPHY answered ${connection.responseCode}")
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }
    }
}
