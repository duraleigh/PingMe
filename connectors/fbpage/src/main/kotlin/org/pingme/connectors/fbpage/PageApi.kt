// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.fbpage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain HTTPS to Meta's Graph API (BUILD_PLAN.md Phase 6, network 7; DESIGN.md 5.2):
 * the only network calls this connector makes, all to graph.facebook.com and the
 * media addresses it hands out. Tests replace it with a pretend Page.
 */
interface PageApi {
    /** GET a Graph path (`me`, `t_123/messages`) with the token and query parameters. */
    suspend fun get(
        path: String,
        token: String,
        params: Map<String, String>,
    ): JsonObject

    /** POST a JSON body to a Graph path. */
    suspend fun post(
        path: String,
        token: String,
        body: JsonObject,
    ): JsonObject

    /** POST a file as multipart form data, with the `message` JSON Meta expects beside it. */
    suspend fun upload(
        path: String,
        token: String,
        message: String,
        file: File,
        mime: String,
    ): JsonObject

    /** Fetch a media address (a Facebook CDN link) into a file. */
    suspend fun download(
        url: String,
        dest: File,
    )
}

/** Meta refused a request: its error code, sub-code, and message. */
class GraphException(
    val code: Int,
    val subcode: Int,
    message: String,
) : IOException(message) {
    /** The token no longer works: expired, revoked, or for a Page the user left. */
    val tokenGone get() = code == CODE_TOKEN || code == CODE_PERMISSION

    /** Meta's 24-hour reply window for the person has closed. */
    val windowClosed get() = subcode == SUBCODE_WINDOW || code == CODE_WINDOW

    companion object {
        const val CODE_TOKEN = 190
        const val CODE_PERMISSION = 200
        const val CODE_WINDOW = 1545041
        const val SUBCODE_WINDOW = 2018278
    }
}

internal val graphJson = Json { ignoreUnknownKeys = true }

/** The real thing, over HttpURLConnection, on the IO dispatcher. */
@Singleton
class HttpPageApi
    @Inject
    constructor() : PageApi {
        override suspend fun get(
            path: String,
            token: String,
            params: Map<String, String>,
        ): JsonObject =
            withContext(Dispatchers.IO) {
                val query =
                    (params + ("access_token" to token)).entries.joinToString(
                        "&",
                    ) { (k, v) -> "$k=${encode(v)}" }
                val connection = URL("$GRAPH$path?$query").openConnection() as HttpURLConnection
                exchange(connection)
            }

        override suspend fun post(
            path: String,
            token: String,
            body: JsonObject,
        ): JsonObject =
            withContext(Dispatchers.IO) {
                val connection = URL("$GRAPH$path?access_token=${encode(token)}").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                exchange(connection)
            }

        override suspend fun upload(
            path: String,
            token: String,
            message: String,
            file: File,
            mime: String,
        ): JsonObject =
            withContext(Dispatchers.IO) {
                val boundary = "PingMe${System.nanoTime()}"
                val connection = URL("$GRAPH$path?access_token=${encode(token)}").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.outputStream.buffered().use { out ->
                    out.write(
                        "--$boundary\r\nContent-Disposition: form-data; name=\"message\"\r\n\r\n$message\r\n"
                            .toByteArray(),
                    )
                    out.write(
                        (
                            "--$boundary\r\nContent-Disposition: form-data; name=\"filedata\"; " +
                                "filename=\"${file.name}\"\r\nContent-Type: $mime\r\n\r\n"
                        ).toByteArray(),
                    )
                    file.inputStream().use { it.copyTo(out) }
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }
                exchange(connection)
            }

        override suspend fun download(
            url: String,
            dest: File,
        ) = withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("Facebook answered ${connection.responseCode} for the file")
                }
                val part = File(dest.path + ".part")
                part.outputStream().use { out -> connection.inputStream.use { it.copyTo(out) } }
                if (!part.renameTo(dest)) throw IOException("Could not keep the file at $dest")
            } finally {
                connection.disconnect()
            }
        }

        /** Reads the answer; a Graph error becomes a [GraphException]. */
        private fun exchange(connection: HttpURLConnection): JsonObject {
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                val status = connection.responseCode
                val stream = if (status in SUCCESS) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return answer(status, text)
            } finally {
                connection.disconnect()
            }
        }

        private fun answer(
            status: Int,
            text: String,
        ): JsonObject {
            val parsed = runCatching { graphJson.parseToJsonElement(text).jsonObject }.getOrNull()
            val error = parsed?.get("error")?.jsonObject
            if (error != null) {
                throw GraphException(
                    error["code"]?.jsonPrimitive?.intOrNull ?: status,
                    error["error_subcode"]?.jsonPrimitive?.intOrNull ?: 0,
                    error["message"]?.jsonPrimitive?.contentOrNull ?: "Facebook answered $status",
                )
            }
            if (parsed == null || status !in SUCCESS) {
                throw IOException("Facebook answered $status: ${text.take(MAX_ERROR)}")
            }
            return parsed
        }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        private companion object {
            const val GRAPH = "https://graph.facebook.com/v25.0/"
            const val TIMEOUT_MS = 30_000
            const val MAX_ERROR = 300
            val SUCCESS = 200..299
        }
    }
