// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gvoice.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The JSON shapes the Go bridge produces (gobridge/gv/convert.go), field for field.
 * Timestamps are Unix milliseconds. Unknown fields are ignored so a newer bridge never
 * breaks an older reader.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val gvJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        classDiscriminator = "type"
        encodeDefaults = true
    }

@Serializable
data class GvThread(
    val id: String,
    val phoneNumbers: List<String> = emptyList(),
    val contacts: List<GvMember> = emptyList(),
    val read: Boolean = true,
    val isText: Boolean = true,
    val archived: Boolean = false,
    val spam: Boolean = false,
    val lastAt: Long = 0,
    val messages: List<GvMessage> = emptyList(),
    val paginationToken: String = "",
)

@Serializable
data class GvMember(
    val phone: String,
    val name: String = "",
)

@Serializable
data class GvMessage(
    val id: String,
    val thread: String = "",
    val sender: String = "",
    val fromMe: Boolean = false,
    val timestamp: Long = 0,
    val kind: String = "text",
    val text: String = "",
    val media: List<GvMedia> = emptyList(),
    val read: Boolean = false,
)

@Serializable
data class GvMedia(
    val id: String,
    val mime: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val unsupported: Boolean = false,
)

/** One event from the Go bridge's EventSink, told apart by its "type". */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface GvEvent {
    @Serializable
    @SerialName("connected")
    data class Connected(
        val phone: String = "",
    ) : GvEvent

    /** The push channel is up. */
    @Serializable
    @SerialName("live")
    data object Live : GvEvent

    @Serializable
    @SerialName("cookies")
    data class Cookies(
        val cookies: String = "",
    ) : GvEvent

    @Serializable
    @SerialName("contacts")
    data class Contacts(
        val people: List<GvMember> = emptyList(),
    ) : GvEvent

    @Serializable
    @SerialName("thread")
    data class Thread(
        val thread: GvThread,
    ) : GvEvent

    @Serializable
    @SerialName("inboxLoaded")
    data object InboxLoaded : GvEvent

    @Serializable
    @SerialName("message")
    data class Message(
        val message: GvMessage,
    ) : GvEvent

    @Serializable
    @SerialName("loggedOut")
    data class LoggedOut(
        val reason: String = "",
    ) : GvEvent

    @Serializable
    @SerialName("connectError")
    data class ConnectError(
        val reason: String = "",
        val fatal: Boolean = false,
    ) : GvEvent
}
