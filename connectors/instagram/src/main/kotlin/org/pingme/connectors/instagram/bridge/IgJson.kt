// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The JSON shapes the Go bridge produces (gobridge/ig/convert.go), field for field.
 * Timestamps are Unix milliseconds. Unknown fields are ignored so a newer bridge never
 * breaks an older reader.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val igJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        classDiscriminator = "type"
        encodeDefaults = true
    }

@Serializable
data class IgThread(
    val id: String,
    val longId: String = "",
    val title: String = "",
    val imageUrl: String = "",
    val isGroup: Boolean = false,
    val folder: String = "",
    val systemFolder: String = "",
    val folderTag: String = "",
    val lastMessageAt: Long = 0,
    val markedUnread: Boolean = false,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val readAt: Long = 0,
    val users: List<IgUser> = emptyList(),
    val admins: List<String> = emptyList(),
    val messages: List<IgMessage> = emptyList(),
)

@Serializable
data class IgUser(
    val id: String,
    val igid: String = "",
    val username: String = "",
    val name: String = "",
    val picture: String = "",
    val isMe: Boolean = false,
)

@Serializable
data class IgMessage(
    val id: String,
    val thread: String = "",
    val sender: String = "",
    val timestamp: Long = 0,
    val kind: String = "text",
    val text: String = "",
    val media: List<IgMedia> = emptyList(),
    val share: IgShare? = null,
    val reactions: List<IgReaction> = emptyList(),
    val replyTo: String = "",
    val replyText: String = "",
    val edited: Boolean = false,
    val viewOnce: Boolean = false,
    val viewOnceGone: String = "",
    val unsent: Boolean = false,
)

@Serializable
data class IgMedia(
    val kind: String = "image",
    val url: String = "",
    val previewUrl: String = "",
    val mime: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Int = 0,
    val id: String = "",
    /** The thread, kept with the reference so an address can be fetched by id later. */
    val thread: String = "",
)

@Serializable
data class IgShare(
    val title: String = "",
    val subtitle: String = "",
    val url: String = "",
    val previewUrl: String = "",
    val kind: String = "",
)

@Serializable
data class IgReaction(
    val emoji: String = "",
    val sender: String = "",
    val timestamp: Long = 0,
)

@Serializable
data class IgThreadPage(
    val threads: List<IgThread> = emptyList(),
    val nextCursor: String = "",
)

/** One event from the Go bridge's EventSink, told apart by its "type". */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface IgEvent {
    /** The inbox loaded; [cookies] are the cookies as Instagram refreshed them. */
    @Serializable
    @SerialName("connected")
    data class Connected(
        val id: String = "",
        val cookies: String = "",
    ) : IgEvent

    /** The live connection is up. */
    @Serializable
    @SerialName("live")
    data object Live : IgEvent

    @Serializable
    @SerialName("disconnected")
    data class Disconnected(
        val error: String = "",
        val failures: Int = 0,
    ) : IgEvent

    @Serializable
    @SerialName("loggedOut")
    data class LoggedOut(
        val error: String = "",
    ) : IgEvent

    /** Instagram wants the inbox loaded again from scratch. */
    @Serializable
    @SerialName("resync")
    data object Resync : IgEvent

    @Serializable
    @SerialName("thread")
    data class Thread(
        val thread: IgThread,
    ) : IgEvent

    @Serializable
    @SerialName("inboxLoaded")
    data class InboxLoaded(
        val nextCursor: String = "",
    ) : IgEvent

    @Serializable
    @SerialName("message")
    data class Message(
        val message: IgMessage,
    ) : IgEvent

    @Serializable
    @SerialName("reaction")
    data class Reaction(
        val thread: String = "",
        val message: String,
        val removed: Boolean = false,
        val reaction: IgReaction,
    ) : IgEvent

    @Serializable
    @SerialName("edit")
    data class Edit(
        val thread: String = "",
        val message: String,
        val text: String = "",
        val timestamp: Long = 0,
    ) : IgEvent

    @Serializable
    @SerialName("unsent")
    data class Unsent(
        val thread: String = "",
        val message: String,
    ) : IgEvent

    @Serializable
    @SerialName("threadGone")
    data class ThreadGone(
        val thread: String,
    ) : IgEvent

    @Serializable
    @SerialName("readByMe")
    data class ReadByMe(
        val thread: String,
        val timestamp: Long = 0,
    ) : IgEvent

    @Serializable
    @SerialName("unreadByMe")
    data class UnreadByMe(
        val thread: String,
    ) : IgEvent

    @Serializable
    @SerialName("readReceipt")
    data class ReadReceipt(
        val thread: String,
        val sender: String = "",
        val timestamp: Long = 0,
    ) : IgEvent

    @Serializable
    @SerialName("folder")
    data class Folder(
        val thread: String,
        val folder: String = "",
        val inboxFolder: String = "",
    ) : IgEvent

    @Serializable
    @SerialName("typing")
    data class Typing(
        val thread: String,
        val sender: String = "",
        val typing: Boolean = false,
    ) : IgEvent
}
