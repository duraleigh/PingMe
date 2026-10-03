// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.fbpage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Meta's Graph API replaced by a pretend Page: one conversation with a short history,
 * answers shaped as the real API shapes them, and every send recorded.
 */
class FakePageApi : PageApi {
    val sentTexts = ConcurrentHashMap<String, MutableList<String>>()
    val actions = CopyOnWriteArrayList<String>()
    val uploads = CopyOnWriteArrayList<String>()
    var validToken = TOKEN
    private val counter = AtomicLong(100)
    private val clock = AtomicLong(System.currentTimeMillis() - 60_000)

    class Row(
        val id: String,
        val from: String,
        val fromName: String,
        val text: String,
        val at: Long,
    )

    val conversations = ConcurrentHashMap<String, MutableList<Row>>()
    val updatedAt = ConcurrentHashMap<String, Long>()

    init {
        val history =
            listOf(
                Row("m_h1", SAM, "Sam Ortiz", "What time?", clock.get() - 3000),
                Row("m_h2", PAGE, "Duraleigh Page", "Leaving now", clock.get() - 2000),
                Row("m_h3", SAM, "Sam Ortiz", "See you at 7", clock.get() - 1000),
            )
        conversations[THREAD] = CopyOnWriteArrayList(history)
        updatedAt[THREAD] = history.last().at
    }

    fun now(): Long = clock.addAndGet(1000)

    /** A person writes to the Page; the next poll sees it. */
    fun receive(
        conversation: String,
        text: String,
    ) {
        val at = now()
        conversations.getOrPut(conversation) { CopyOnWriteArrayList() } +=
            Row("m_${counter.incrementAndGet()}", SAM, "Sam Ortiz", text, at)
        updatedAt[conversation] = at
    }

    override suspend fun get(
        path: String,
        token: String,
        params: Map<String, String>,
    ): JsonObject {
        checkToken(token)
        return when {
            path == "me" -> {
                buildJsonObject {
                    put("id", PAGE)
                    put("name", "Duraleigh Page")
                }
            }

            path == "$PAGE/conversations" -> {
                buildJsonObject {
                    putJsonArray("data") {
                        conversations.keys.sortedByDescending { updatedAt[it] ?: 0 }.forEach { add(conversation(it)) }
                    }
                }
            }

            path.endsWith("/messages") -> {
                val id = path.removeSuffix("/messages")
                val rows = conversations[id].orEmpty().sortedByDescending { it.at }
                val limit = params["limit"]?.toInt() ?: 25
                val start = params["after"]?.toInt() ?: 0
                val page = rows.drop(start).take(limit)
                buildJsonObject {
                    putJsonArray("data") { page.forEach { add(message(it)) } }
                    if (start + limit < rows.size) {
                        putJsonObject("paging") {
                            putJsonObject("cursors") { put("after", (start + limit).toString()) }
                            put("next", "https://graph.facebook.com/next")
                        }
                    }
                }
            }

            else -> {
                error("unexpected GET $path")
            }
        }
    }

    override suspend fun post(
        path: String,
        token: String,
        body: JsonObject,
    ): JsonObject {
        checkToken(token)
        require(path == "$PAGE/messages") { "unexpected POST $path" }
        val recipient = body["recipient"]!!.jsonObject["id"]!!.jsonPrimitive.content
        body["sender_action"]?.jsonPrimitive?.contentOrNull?.let {
            actions += "$recipient:$it"
            return buildJsonObject { put("recipient_id", recipient) }
        }
        val message = body["message"]!!.jsonObject
        val text = message["text"]?.jsonPrimitive?.contentOrNull
        val id = "m_${counter.incrementAndGet()}"
        val conversation = conversations.keys.first { conv -> conversations[conv]!!.any { it.from == recipient } }
        if (text != null) {
            sentTexts.getOrPut(conversation) { CopyOnWriteArrayList() } += text
            conversations[conversation]!! += Row(id, PAGE, "Duraleigh Page", text, now())
        } else {
            conversations[conversation]!! += Row(id, PAGE, "Duraleigh Page", "", now())
        }
        return buildJsonObject {
            put("recipient_id", recipient)
            put("message_id", id)
        }
    }

    override suspend fun upload(
        path: String,
        token: String,
        message: String,
        file: File,
        mime: String,
    ): JsonObject {
        checkToken(token)
        uploads += "${file.name}:$mime"
        return buildJsonObject { put("attachment_id", "att_${counter.incrementAndGet()}") }
    }

    override suspend fun download(
        url: String,
        dest: File,
    ) {
        dest.writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun checkToken(token: String) {
        if (token != validToken) throw GraphException(GraphException.CODE_TOKEN, 0, "Invalid OAuth access token")
    }

    private fun conversation(id: String) =
        buildJsonObject {
            put("id", id)
            put("updated_time", time(updatedAt[id] ?: 0))
            putJsonObject("participants") {
                putJsonArray("data") {
                    add(
                        buildJsonObject {
                            put("id", SAM)
                            put("name", "Sam Ortiz")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("id", PAGE)
                            put("name", "Duraleigh Page")
                        },
                    )
                }
            }
            putJsonObject("messages") {
                putJsonArray("data") {
                    conversations[id]
                        .orEmpty()
                        .sortedByDescending { it.at }
                        .take(10)
                        .forEach { add(message(it)) }
                }
            }
        }

    private fun message(row: Row) =
        buildJsonObject {
            put("id", row.id)
            put("created_time", time(row.at))
            putJsonObject("from") {
                put("id", row.from)
                put("name", row.fromName)
            }
            put("message", row.text)
        }

    companion object {
        const val TOKEN = "EAAB-test"
        const val PAGE = "1000"
        const val SAM = "200"
        const val THREAD = "t_1"
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC)

        fun time(at: Long): String = TIME.format(Instant.ofEpochMilli(at))

        fun jsonArrayOf(vararg items: JsonObject) = buildJsonArray { items.forEach { add(it) } }
    }
}
