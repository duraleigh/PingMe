// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The JSON shapes the Go bridge produces (gobridge/sig/convert.go), field for field.
 * Timestamps are Unix milliseconds. Unknown fields are ignored so a newer bridge never
 * breaks an older reader.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val sigJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        classDiscriminator = "type"
        encodeDefaults = true
    }

@Serializable
data class SigChat(
    val id: String,
    val isGroup: Boolean = false,
    val name: String = "",
    val members: List<SigMember> = emptyList(),
    val unread: Int = 0,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val muteUntil: Long = 0,
    val lastAt: Long = 0,
    val count: Int = 0,
)

/** One number Signal's directory knows: the chat id to use for it. */
@Serializable
data class SigLookup(
    val phone: String,
    val id: String,
)

@Serializable
data class SigMember(
    val id: String,
    val phone: String = "",
    val name: String = "",
    val isMe: Boolean = false,
    val isAdmin: Boolean = false,
)

@Serializable
data class SigMessage(
    val id: String,
    val chat: String = "",
    val sender: String = "",
    val senderPhone: String = "",
    val fromMe: Boolean = false,
    val timestamp: Long = 0,
    val kind: String = "text",
    val text: String = "",
    val media: SigMedia? = null,
    val quote: SigQuote? = null,
    val reaction: SigReaction? = null,
    val revoke: SigTarget? = null,
    val edit: SigEdit? = null,
    val typing: Boolean? = null,
    val status: String = "",
    val reactions: List<SigReaction> = emptyList(),
    val expiresIn: Long = 0,
    val read: Boolean = false,
)

@Serializable
data class SigMedia(
    val pointer: String = "",
    val mime: String = "application/octet-stream",
    val fileName: String = "",
    val size: Long = 0,
    val width: Long = 0,
    val height: Long = 0,
    val voice: Boolean = false,
    val gif: Boolean = false,
    val caption: String = "",
)

@Serializable
data class SigQuote(
    val id: String = "",
    val sender: String = "",
    val text: String = "",
)

@Serializable
data class SigReaction(
    val targetSender: String = "",
    val targetTimestamp: Long = 0,
    val sender: String = "",
    val emoji: String = "",
    val remove: Boolean = false,
    val timestamp: Long = 0,
)

@Serializable
data class SigTarget(
    val timestamp: Long = 0,
)

@Serializable
data class SigEdit(
    val targetTimestamp: Long = 0,
    val text: String = "",
)

@Serializable
data class SigReceipt(
    val sender: String = "",
    val kind: String = "delivered",
    val timestamps: List<Long> = emptyList(),
)

@Serializable
data class SigReadMark(
    val sender: String = "",
    val timestamp: Long = 0,
)

/** One event from the Go bridge's EventSink, told apart by its "type". */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface SigEvent {
    @Serializable
    @SerialName("linkQr")
    data class LinkQr(
        val url: String = "",
        @SerialName("try") val attempt: Int = 0,
    ) : SigEvent

    @Serializable
    @SerialName("linkDone")
    data class LinkDone(
        val id: String = "",
        val phone: String = "",
    ) : SigEvent

    @Serializable
    @SerialName("linkError")
    data class LinkError(
        val reason: String = "",
    ) : SigEvent

    @Serializable
    @SerialName("connected")
    data class Connected(
        val id: String = "",
        val phone: String = "",
    ) : SigEvent

    @Serializable
    @SerialName("disconnected")
    data object Disconnected : SigEvent

    @Serializable
    @SerialName("loggedOut")
    data class LoggedOut(
        val reason: String = "",
    ) : SigEvent

    @Serializable
    @SerialName("connectError")
    data class ConnectError(
        val reason: String = "",
        val fatal: Boolean = false,
    ) : SigEvent

    /** The phone's history archive: "waiting", "fetching", "done", "declined", "failed". */
    @Serializable
    @SerialName("transfer")
    data class Transfer(
        val state: String = "",
        val reason: String = "",
    ) : SigEvent

    @Serializable
    @SerialName("chats")
    data class Chats(
        val chats: List<SigChat> = emptyList(),
    ) : SigEvent

    @Serializable
    @SerialName("chat")
    data class Chat(
        val chat: SigChat,
    ) : SigEvent

    @Serializable
    @SerialName("message")
    data class Message(
        val message: SigMessage,
    ) : SigEvent

    @Serializable
    @SerialName("receipt")
    data class Receipt(
        val receipt: SigReceipt,
    ) : SigEvent

    /** You read these messages on another of your devices. */
    @Serializable
    @SerialName("readSelf")
    data class ReadSelf(
        val messages: List<SigReadMark> = emptyList(),
    ) : SigEvent

    @Serializable
    @SerialName("contacts")
    data object Contacts : SigEvent

    @Serializable
    @SerialName("undecryptable")
    data class Undecryptable(
        val sender: String = "",
        val timestamp: Long = 0,
    ) : SigEvent

    @Serializable
    @SerialName("queueEmpty")
    data object QueueEmpty : SigEvent

    @Serializable
    @SerialName("typing")
    data class Typing(
        val chat: String = "",
        val sender: String = "",
        val typing: Boolean = false,
    ) : SigEvent
}
