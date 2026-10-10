// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The JSON shapes the Go bridge produces (gobridge/fb/convert.go), field for field.
 * Timestamps are Unix milliseconds. Unknown fields are ignored so a newer bridge never
 * breaks an older reader.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val fbJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        classDiscriminator = "type"
        encodeDefaults = true
    }

@Serializable
data class FbThread(
    val id: String,
    val title: String = "",
    val imageUrl: String = "",
    val isGroup: Boolean = false,
    /** Messenger's folder: "inbox", "pending", "other", "spam", or "archived". */
    val folder: String = "",
    val lastMessageAt: Long = 0,
    val readAt: Long = 0,
    val muted: Boolean = false,
    val users: List<FbUser> = emptyList(),
    val admins: List<String> = emptyList(),
    val messages: List<FbMessage> = emptyList(),
    val moreBefore: Boolean = false,
)

@Serializable
data class FbUser(
    val id: String,
    val name: String = "",
    val picture: String = "",
    val isMe: Boolean = false,
)

@Serializable
data class FbMessage(
    val id: String,
    val thread: String = "",
    val sender: String = "",
    val timestamp: Long = 0,
    val kind: String = "text",
    val text: String = "",
    val media: List<FbMedia> = emptyList(),
    val share: FbShare? = null,
    val reactions: List<FbReaction> = emptyList(),
    val replyTo: String = "",
    val replyText: String = "",
    val edited: Boolean = false,
    val unsent: Boolean = false,
)

@Serializable
data class FbMedia(
    val kind: String = "image",
    val url: String = "",
    val previewUrl: String = "",
    val mime: String = "",
    val fileName: String = "",
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Int = 0,
    val id: String = "",
)

@Serializable
data class FbShare(
    val title: String = "",
    val subtitle: String = "",
    val url: String = "",
    val previewUrl: String = "",
)

@Serializable
data class FbReaction(
    val emoji: String = "",
    val sender: String = "",
    val timestamp: Long = 0,
)

/** One event from the Go bridge's EventSink, told apart by its "type". */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface FbEvent {
    /** The inbox page loaded; [cookies] are the cookies as Messenger refreshed them. */
    @Serializable
    @SerialName("connected")
    data class Connected(
        val id: String = "",
        val cookies: String = "",
    ) : FbEvent

    /** The encrypted channel's state: "connected", or "failed" with why. */
    @Serializable
    @SerialName("e2ee")
    data class E2ee(
        val state: String,
        val error: String = "",
    ) : FbEvent

    /** The live connection is up. */
    @Serializable
    @SerialName("live")
    data object Live : FbEvent

    /** The live connection dropped; the bridge retries unless [permanent]. */
    @Serializable
    @SerialName("disconnected")
    data class Disconnected(
        val error: String = "",
        val permanent: Boolean = false,
    ) : FbEvent

    @Serializable
    @SerialName("loggedOut")
    data class LoggedOut(
        val error: String = "",
    ) : FbEvent

    @Serializable
    @SerialName("thread")
    data class Thread(
        val thread: FbThread,
    ) : FbEvent

    @Serializable
    @SerialName("inboxLoaded")
    data object InboxLoaded : FbEvent

    @Serializable
    @SerialName("message")
    data class Message(
        val message: FbMessage,
    ) : FbEvent

    @Serializable
    @SerialName("reaction")
    data class Reaction(
        val thread: String = "",
        val message: String,
        val removed: Boolean = false,
        val reaction: FbReaction,
    ) : FbEvent

    @Serializable
    @SerialName("edit")
    data class Edit(
        val thread: String = "",
        val message: String,
        val text: String = "",
        val timestamp: Long = 0,
    ) : FbEvent

    @Serializable
    @SerialName("unsent")
    data class Unsent(
        val thread: String = "",
        val message: String,
    ) : FbEvent

    @Serializable
    @SerialName("threadGone")
    data class ThreadGone(
        val thread: String,
    ) : FbEvent

    @Serializable
    @SerialName("readByMe")
    data class ReadByMe(
        val thread: String,
        val timestamp: Long = 0,
    ) : FbEvent

    @Serializable
    @SerialName("readReceipt")
    data class ReadReceipt(
        val thread: String,
        val sender: String = "",
        val timestamp: Long = 0,
    ) : FbEvent

    @Serializable
    @SerialName("typing")
    data class Typing(
        val thread: String,
        val sender: String = "",
        val typing: Boolean = false,
    ) : FbEvent
}
