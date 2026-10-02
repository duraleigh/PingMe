// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The JSON shapes the Go bridge produces (gobridge/wa/convert.go), field for field.
 * Timestamps are Unix milliseconds; keys are base64. Unknown fields are ignored so a
 * newer bridge never breaks an older reader.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val waJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        classDiscriminator = "type"
        encodeDefaults = true
    }

@Serializable
data class WaChat(
    val id: String,
    val name: String = "",
    val isGroup: Boolean = false,
    val unread: Int = 0,
    val lastMessageAt: Long = 0,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val communityId: String = "",
    val isCommunity: Boolean = false,
    val participants: List<WaParticipant> = emptyList(),
)

@Serializable
data class WaParticipant(
    val id: String,
    val phone: String = "",
    val name: String = "",
    val isMe: Boolean = false,
    val isAdmin: Boolean = false,
)

@Serializable
data class WaMessage(
    val id: String,
    val chat: String,
    val sender: String = "",
    val senderPhone: String = "",
    val pushName: String = "",
    val fromMe: Boolean = false,
    val timestamp: Long = 0,
    val kind: String = "text",
    val text: String = "",
    val media: WaMedia? = null,
    val reaction: WaReaction? = null,
    val revoke: WaTarget? = null,
    val edit: WaEdit? = null,
    val replyTo: WaQuote? = null,
    val location: WaLocation? = null,
    val contact: WaContact? = null,
    val status: String = "",
    val isViewOnce: Boolean = false,
    val isEphemeral: Boolean = false,
    val edited: Boolean = false,
    val system: String = "",
    val mentions: List<String> = emptyList(),
)

@Serializable
data class WaMedia(
    val type: String = "",
    val mime: String = "application/octet-stream",
    val directPath: String = "",
    val url: String = "",
    val mediaKey: String = "",
    val fileSha256: String = "",
    val fileEncSha256: String = "",
    val fileLength: Long = 0,
    val width: Long = 0,
    val height: Long = 0,
    val seconds: Long = 0,
    val fileName: String = "",
    val caption: String = "",
    val voice: Boolean = false,
    val gif: Boolean = false,
    val animated: Boolean = false,
)

@Serializable
data class WaReaction(
    val targetId: String,
    val targetSender: String = "",
    val targetFromMe: Boolean = false,
    val emoji: String = "",
)

@Serializable
data class WaTarget(
    val id: String,
    val sender: String = "",
    val fromMe: Boolean = false,
)

@Serializable
data class WaEdit(
    val targetId: String,
    val text: String = "",
)

@Serializable
data class WaQuote(
    val id: String,
    val sender: String = "",
    val text: String = "",
)

@Serializable
data class WaLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val name: String = "",
)

@Serializable
data class WaContact(
    val displayName: String = "",
    val vcard: String = "",
)

@Serializable
data class WaReceipt(
    val chat: String,
    val sender: String = "",
    val ids: List<String> = emptyList(),
    val kind: String = "delivered",
    val timestamp: Long = 0,
    val fromMe: Boolean = false,
)

/** What a reply names, for SendText and SendMedia. */
@Serializable
data class WaReply(
    val id: String,
    val sender: String = "",
    val fromMe: Boolean = false,
    val text: String = "",
)

/** One event from the Go bridge's EventSink, told apart by its "type". */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface WaEvent {
    @Serializable
    @SerialName("connected")
    data class Connected(
        val id: String = "",
        val phone: String = "",
        val lid: String = "",
        val pushName: String = "",
    ) : WaEvent

    @Serializable
    @SerialName("disconnected")
    data object Disconnected : WaEvent

    @Serializable
    @SerialName("pairSuccess")
    data class PairSuccess(
        val id: String = "",
        val phone: String = "",
    ) : WaEvent

    @Serializable
    @SerialName("pairError")
    data class PairError(
        val error: String = "",
    ) : WaEvent

    /** WhatsApp has unlinked this device; the user must link again. */
    @Serializable
    @SerialName("loggedOut")
    data class LoggedOut(
        val reason: String = "",
    ) : WaEvent

    @Serializable
    @SerialName("streamReplaced")
    data object StreamReplaced : WaEvent

    @Serializable
    @SerialName("temporaryBan")
    data class TemporaryBan(
        val reason: String = "",
        val expireSeconds: Long = 0,
    ) : WaEvent

    @Serializable
    @SerialName("clientOutdated")
    data object ClientOutdated : WaEvent

    @Serializable
    @SerialName("keepAliveTimeout")
    data class KeepAliveTimeout(
        val errors: Int = 0,
    ) : WaEvent

    @Serializable
    @SerialName("connectFailure")
    data class ConnectFailure(
        val reason: String = "",
        val message: String = "",
    ) : WaEvent

    @Serializable
    @SerialName("offlineSyncDone")
    data class OfflineSyncDone(
        val count: Int = 0,
    ) : WaEvent

    @Serializable
    @SerialName("message")
    data class Message(
        val message: WaMessage,
    ) : WaEvent

    @Serializable
    @SerialName("undecryptable")
    data class Undecryptable(
        val id: String = "",
        val chat: String = "",
        val sender: String = "",
        val timestamp: Long = 0,
        val fromMe: Boolean = false,
    ) : WaEvent

    @Serializable
    @SerialName("receipt")
    data class Receipt(
        val receipt: WaReceipt,
    ) : WaEvent

    @Serializable
    @SerialName("typing")
    data class Typing(
        val chat: String,
        val sender: String = "",
        val typing: Boolean = false,
    ) : WaEvent

    /** One conversation from a history sync, with its messages. */
    @Serializable
    @SerialName("history")
    data class History(
        val syncType: String = "",
        val progress: Int = 0,
        val chat: WaChat,
        val messages: List<WaMessage> = emptyList(),
    ) : WaEvent

    @Serializable
    @SerialName("historyChunk")
    data class HistoryChunk(
        val syncType: String = "",
        val progress: Int = 0,
        val chats: Int = 0,
    ) : WaEvent

    @Serializable
    @SerialName("group")
    data class Group(
        val chat: WaChat,
    ) : WaEvent

    @Serializable
    @SerialName("chatRead")
    data class ChatRead(
        val chat: String,
        val read: Boolean = true,
    ) : WaEvent

    @Serializable
    @SerialName("pushName")
    data class PushName(
        val id: String = "",
        val name: String = "",
    ) : WaEvent
}
