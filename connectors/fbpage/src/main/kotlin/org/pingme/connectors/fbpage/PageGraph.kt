// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.fbpage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

// What the Graph API hands back, read into plain shapes (field names as Meta documents them).

data class PageInfo(
    val id: String,
    val name: String,
)

data class PageUser(
    val id: String,
    val name: String,
)

data class PageConversation(
    val id: String,
    val updatedAt: Long,
    val participants: List<PageUser>,
    val messages: List<PageMessage>,
)

data class PageMessage(
    val id: String,
    val createdAt: Long,
    val from: PageUser,
    val text: String,
    val attachments: List<PageAttachment>,
    val shares: List<PageShare>,
    val sticker: String,
    val unsupported: Boolean,
)

/** One file on a message: a picture, a video (or animated GIF), or any other file. */
data class PageAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val url: String,
    val previewUrl: String,
    val width: Int,
    val height: Int,
    val lengthMs: Long,
    val kind: String, // "image", "gif", "video", "file"
)

data class PageShare(
    val link: String,
    val name: String,
    val description: String,
)

data class PageMessages(
    val messages: List<PageMessage>,
    val nextCursor: String,
)

/** The calls the connector makes, typed, over a [PageApi]. */
class PageGraph(
    private val api: PageApi,
) {
    /** The Page the token belongs to; a bad token raises [GraphException]. */
    suspend fun me(token: String): PageInfo {
        val me = api.get("me", token, mapOf("fields" to "id,name"))
        return PageInfo(me.string("id"), me.string("name"))
    }

    /** The Page's Messenger conversations, newest first, each with its newest messages. */
    suspend fun conversations(
        pageId: String,
        token: String,
        limit: Int = CONVERSATIONS,
    ): List<PageConversation> {
        val answer =
            api.get(
                "$pageId/conversations",
                token,
                mapOf("platform" to "messenger", "limit" to limit.toString(), "fields" to CONVERSATION_FIELDS),
            )
        return answer.list("data").map(::conversation)
    }

    /** A page of a conversation's messages, newest first; [after] is the cursor of the previous page. */
    suspend fun messages(
        conversationId: String,
        token: String,
        after: String,
        limit: Int,
    ): PageMessages {
        val params = mutableMapOf("fields" to MESSAGE_FIELDS, "limit" to limit.toString())
        if (after.isNotEmpty()) params["after"] = after
        val answer = api.get("$conversationId/messages", token, params)
        val next =
            answer["paging"]
                ?.jsonObject
                ?.get("cursors")
                ?.jsonObject
                ?.get("after")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        val hasNext = answer["paging"]?.jsonObject?.get("next") != null
        return PageMessages(answer.list("data").map(::message), if (hasNext) next else "")
    }

    /** Sends text to a person (by page-scoped id) as a reply; returns Meta's message id. */
    suspend fun sendText(
        pageId: String,
        token: String,
        personId: String,
        text: String,
    ): String =
        api
            .post(
                "$pageId/messages",
                token,
                buildJsonObject {
                    putJsonObject("recipient") { put("id", personId) }
                    put("messaging_type", "RESPONSE")
                    putJsonObject("message") { put("text", text) }
                },
            ).string("message_id")

    /** Uploads a file to Meta first (the phone has no public address), then sends it. */
    suspend fun sendFile(
        pageId: String,
        token: String,
        personId: String,
        file: File,
        mime: String,
        kind: String,
    ): String {
        val message = """{"attachment":{"type":"$kind","payload":{"is_reusable":false}}}"""
        val attachmentId = api.upload("$pageId/message_attachments", token, message, file, mime).string("attachment_id")
        return api
            .post(
                "$pageId/messages",
                token,
                buildJsonObject {
                    putJsonObject("recipient") { put("id", personId) }
                    put("messaging_type", "RESPONSE")
                    putJsonObject("message") {
                        putJsonObject("attachment") {
                            put("type", kind)
                            putJsonObject("payload") { put("attachment_id", attachmentId) }
                        }
                    }
                },
            ).string("message_id")
    }

    /** "mark_seen", "typing_on", or "typing_off" toward a person. */
    suspend fun senderAction(
        pageId: String,
        token: String,
        personId: String,
        action: String,
    ) {
        api.post(
            "$pageId/messages",
            token,
            buildJsonObject {
                putJsonObject("recipient") { put("id", personId) }
                put("sender_action", action)
            },
        )
    }

    /** A media address Meta handed out, into a file. */
    suspend fun download(
        url: String,
        dest: File,
    ) = api.download(url, dest)

    private fun conversation(o: JsonObject) =
        PageConversation(
            id = o.string("id"),
            updatedAt = parseTime(o.string("updated_time")),
            participants =
                o["participants"]
                    ?.jsonObject
                    ?.list("data")
                    ?.map(::user)
                    .orEmpty(),
            messages =
                o["messages"]
                    ?.jsonObject
                    ?.list("data")
                    ?.map(::message)
                    .orEmpty(),
        )

    private fun user(o: JsonObject) = PageUser(o.string("id"), o.string("name"))

    private fun message(o: JsonObject) =
        PageMessage(
            id = o.string("id"),
            createdAt = parseTime(o.string("created_time")),
            from = o["from"]?.jsonObject?.let(::user) ?: PageUser("", ""),
            text = o.string("message"),
            attachments =
                o["attachments"]
                    ?.jsonObject
                    ?.list("data")
                    ?.map(::attachment)
                    .orEmpty(),
            shares =
                o["shares"]
                    ?.jsonObject
                    ?.list("data")
                    ?.map(::share)
                    .orEmpty(),
            sticker = o.string("sticker"),
            unsupported = o["is_unsupported"]?.jsonPrimitive?.booleanOrNull ?: false,
        )

    private fun attachment(o: JsonObject): PageAttachment {
        val image = o["image_data"]?.jsonObject
        val video = o["video_data"]?.jsonObject
        val mime = o.string("mime_type")
        val gifUrl = video?.string("animated_gif_url").orEmpty()
        return when {
            video != null -> {
                PageAttachment(
                    id = o.string("id"),
                    name = o.string("name"),
                    mime = mime.ifEmpty { if (gifUrl.isNotEmpty()) "image/gif" else "video/mp4" },
                    url = gifUrl.ifEmpty { video.string("url") },
                    previewUrl = video.string("preview_url"),
                    width = video.int("width"),
                    height = video.int("height"),
                    lengthMs = (video["length"]?.jsonPrimitive?.doubleOrNull ?: 0.0).times(MS).toLong(),
                    kind = if (gifUrl.isNotEmpty()) "gif" else "video",
                )
            }

            image != null -> {
                PageAttachment(
                    id = o.string("id"),
                    name = o.string("name"),
                    mime = mime.ifEmpty { "image/jpeg" },
                    url = image.string("url"),
                    previewUrl = image.string("preview_url"),
                    width = image.int("width"),
                    height = image.int("height"),
                    lengthMs = 0,
                    kind = if (mime == "image/gif") "gif" else "image",
                )
            }

            else -> {
                PageAttachment(
                    id = o.string("id"),
                    name = o.string("name"),
                    mime = mime.ifEmpty { "application/octet-stream" },
                    url = o.string("file_url"),
                    previewUrl = "",
                    width = 0,
                    height = 0,
                    lengthMs = 0,
                    kind = "file",
                )
            }
        }
    }

    private fun share(o: JsonObject) = PageShare(o.string("link"), o.string("name"), o.string("description"))

    companion object {
        const val CONVERSATIONS = 25
        private const val NEWEST = 10
        private const val MS = 1000.0
        const val MESSAGE_FIELDS =
            "id,created_time,from,message,sticker,is_unsupported," +
                "attachments{id,name,mime_type,file_url,image_data,video_data},shares{link,name,description}"
        val CONVERSATION_FIELDS = "id,updated_time,participants,messages.limit($NEWEST){$MESSAGE_FIELDS}"

        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

        /** Meta's "2026-10-02T23:15:04+0000" as Unix milliseconds; 0 when unreadable. */
        fun parseTime(text: String): Long =
            runCatching { OffsetDateTime.parse(text, TIME).toInstant().toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
                .getOrDefault(0L)

        private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

        private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0

        private fun JsonObject.list(key: String): List<JsonObject> =
            (this[key] as? JsonArray)?.jsonArray?.map { it.jsonObject }.orEmpty()
    }
}
