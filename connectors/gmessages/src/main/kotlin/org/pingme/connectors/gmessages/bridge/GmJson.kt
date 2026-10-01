// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The JSON shapes the Go bridge produces (gobridge/gm/convert.go), field for field.
 * Timestamps are Unix microseconds; keys are base64. Unknown fields are ignored so a
 * newer bridge never breaks an older reader.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val gmJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        classDiscriminator = "type"
    }

@Serializable
data class GmConversation(
    val id: String,
    val name: String = "",
    val isGroup: Boolean = false,
    val type: String = "UNKNOWN",
    val status: String = "ACTIVE",
    val sendMode: String = "",
    val unread: Boolean = false,
    val readOnly: Boolean = false,
    val pinned: Boolean = false,
    val lastMessageAt: Long = 0,
    val latestMessageId: String = "",
    val outgoingId: String = "",
    val groupAvatarUrl: String = "",
    val preview: GmPreview? = null,
    val participants: List<GmParticipant> = emptyList(),
    val otherParticipantIds: List<String> = emptyList(),
    val outgoingIsRcs: Boolean = false,
)

@Serializable
data class GmPreview(
    val text: String = "",
    val fromMe: Boolean = false,
    val displayName: String = "",
)

@Serializable
data class GmParticipant(
    val id: String,
    val number: String = "",
    val formattedNumber: String = "",
    val firstName: String = "",
    val fullName: String = "",
    val isMe: Boolean = false,
    val isVisible: Boolean = true,
    val contactId: String = "",
    val avatarHexColor: String = "",
)

@Serializable
data class GmMessage(
    val id: String,
    val conversationId: String,
    val participantId: String = "",
    val timestamp: Long,
    val status: Int = 0,
    val statusName: String = "",
    val statusText: String = "",
    val direction: String = "unknown",
    val fromMe: Boolean = false,
    val sent: Boolean = false,
    val delivered: Boolean = false,
    val read: Boolean = false,
    val failed: Boolean = false,
    val hide: Boolean = false,
    val type: Long = 0,
    val transport: String = "RCS",
    val tmpId: String = "",
    val subject: String = "",
    val text: String = "",
    val media: List<GmMedia> = emptyList(),
    val reactions: List<GmReaction> = emptyList(),
    val replyToId: String = "",
    val pendingDownload: String = "",
    val failReason: String = "",
    val sender: GmParticipant? = null,
)

@Serializable
data class GmMedia(
    val partId: String = "",
    val mediaId: String = "",
    val thumbnailMediaId: String = "",
    val name: String = "",
    val mime: String = "application/octet-stream",
    val format: Int = 0,
    val size: Long = 0,
    val width: Long = 0,
    val height: Long = 0,
    val key: String = "",
    val thumbnailKey: String = "",
    val pending: Boolean = false,
)

@Serializable
data class GmReaction(
    val emoji: String,
    val participantIds: List<String> = emptyList(),
)

@Serializable
data class GmCursor(
    val lastItemId: String,
    val lastItemTimestamp: Long,
)

@Serializable
data class GmConversationPage(
    val conversations: List<GmConversation> = emptyList(),
    val cursor: GmCursor? = null,
)

@Serializable
data class GmMessagePage(
    val messages: List<GmMessage> = emptyList(),
    val cursor: GmCursor? = null,
    val total: Long = 0,
)

@Serializable
data class GmSim(
    val participantId: String,
    val simNumber: Int = 0,
    val carrierName: String = "",
    val colorHex: String = "",
    val phoneNumber: String = "",
    val rcsEnabled: Boolean = false,
)

@Serializable
data class GmSettings(
    val rcsEnabled: Boolean = false,
    val readReceipts: Boolean = false,
    val typingIndicators: Boolean = false,
    val isDefaultSmsApp: Boolean? = null,
    val sims: List<GmSim> = emptyList(),
)

/** What Login.finish gives back. */
@Serializable
data class GmLoginResult(
    val auth: String,
    val phoneId: String,
    val email: String,
)

/** What Session.sendMessage takes. */
@Serializable
data class GmSendRequest(
    val conversationId: String,
    val tmpId: String,
    val text: String = "",
    val replyToId: String = "",
    val media: List<GmMedia> = emptyList(),
)

/** One event from the Go bridge's EventSink, told apart by its "type". */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface GmEvent {
    /** The phone accepted this session as its web client; [resync] asks for a fresh chat list. */
    @Serializable
    @SerialName("ready")
    data class Ready(
        val resync: Boolean = false,
    ) : GmEvent

    /** Another web client took over. The bridge tries to take the session back itself. */
    @Serializable
    @SerialName("inactive")
    data class Inactive(
        val reason: String = "",
    ) : GmEvent

    @Serializable
    @SerialName("phone")
    data class Phone(
        val responding: Boolean,
    ) : GmEvent

    @Serializable
    @SerialName("temporaryError")
    data class TemporaryError(
        val error: String = "",
    ) : GmEvent

    @Serializable
    @SerialName("recovered")
    data object Recovered : GmEvent

    /** The long poll died; the supervisor reconnects. */
    @Serializable
    @SerialName("fatal")
    data class Fatal(
        val error: String = "",
    ) : GmEvent

    /** Google no longer accepts the pairing; the user must pair again. */
    @Serializable
    @SerialName("loggedOut")
    data class LoggedOut(
        val reason: String = "",
        val error: String = "",
    ) : GmEvent

    /** The session's tokens changed: save [auth] again. */
    @Serializable
    @SerialName("authUpdated")
    data class AuthUpdated(
        val auth: String,
    ) : GmEvent

    @Serializable
    @SerialName("conversation")
    data class Conversation(
        val conversation: GmConversation,
    ) : GmEvent

    @Serializable
    @SerialName("message")
    data class Message(
        val message: GmMessage,
        val isOld: Boolean = false,
    ) : GmEvent

    @Serializable
    @SerialName("typing")
    data class Typing(
        val conversationId: String,
        val number: String = "",
        val typing: Boolean,
    ) : GmEvent

    @Serializable
    @SerialName("settings")
    data class Settings(
        val settings: GmSettings,
    ) : GmEvent

    @Serializable
    @SerialName("accountChange")
    data class AccountChange(
        val account: String = "",
        val enabled: Boolean = false,
        val fake: Boolean = false,
    ) : GmEvent

    /** Nothing has arrived for a long while; a chat-list check is in order. */
    @Serializable
    @SerialName("noData")
    data object NoData : GmEvent

    @Serializable
    @SerialName("phoneSynced")
    data object PhoneSynced : GmEvent

    @Serializable
    @SerialName("battery")
    data class Battery(
        val low: Boolean,
    ) : GmEvent

    /** The bridge gave up on this connection; drop it and connect again. */
    @Serializable
    @SerialName("reconnect")
    data object Reconnect : GmEvent
}
