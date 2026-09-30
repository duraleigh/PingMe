// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Message(
    val id: MessageId,
    val chatId: ChatId,
    val senderId: PersonId,
    val sentAt: Instant,
    val receivedAt: Instant,
    val body: String?,
    val kind: MessageKind,
    val attachments: List<Attachment>,
    val replyTo: MessageId?,
    /** Quoted text for replies that are not native, such as SMS (UI_DESIGN.md 5.2). */
    val quote: Quote?,
    val editedAt: Instant?,
    val deletedForEveryone: Boolean,
    val status: MessageStatus,
    val reactions: List<Reaction>,
    val transport: Transport,
    val networkRemoteId: String,
    val linkPreview: LinkPreview?,
    val isOutgoing: Boolean,
)

@Serializable
enum class MessageKind {
    TEXT,
    VOICE,
    GIF,
    IMAGE,
    VIDEO,
    FILE,
    LOCATION,
    CONTACT,
    STICKER,
    DELETED,
}

@Serializable
sealed interface MessageStatus {
    @Serializable
    data object Sending : MessageStatus

    @Serializable
    data object Sent : MessageStatus

    @Serializable
    data object Delivered : MessageStatus

    @Serializable
    data object Read : MessageStatus

    @Serializable
    data class Failed(
        val reason: String,
    ) : MessageStatus

    /** A send-later message waiting for its time (UI_DESIGN.md 10.13). */
    @Serializable
    data class Scheduled(
        val at: Instant,
    ) : MessageStatus
}

/** How a message travelled. RCS, SMS, and MMS come through Google Messages or native SMS. */
@Serializable
enum class Transport {
    RCS,
    SMS,
    MMS,

    /** The network's own transport (WhatsApp, Signal, Telegram, ...). */
    NETWORK,
}

@Serializable
data class Attachment(
    val id: AttachmentId,
    val kind: AttachmentKind,
    val mimeType: String,
    /** The file's name as sent; indexed for search (BUILD_PLAN.md P1.2, attachmentNames). */
    val fileName: String?,
    val sizeBytes: Long,
    /** File in app storage once downloaded; media is fetched lazily (DESIGN.md 6.3). */
    val localPath: String?,
    val remoteRef: String?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    /** View-once or disappearing media (UI_DESIGN.md 10.16). */
    val isEphemeral: Boolean,
    /** When "Save all incoming media" copied it to the chosen folder. */
    val savedAt: Instant?,
)

@Serializable
enum class AttachmentKind {
    IMAGE,
    VIDEO,
    AUDIO,
    VOICE,
    GIF,
    STICKER,
    FILE,
    CONTACT,
    LOCATION,
}

@Serializable
data class Reaction(
    val emoji: String,
    val senderId: PersonId,
    val at: Instant,
)

@Serializable
data class Quote(
    val senderName: String,
    val text: String,
)

@Serializable
data class LinkPreview(
    val url: String,
    /** The link with tracking parameters removed (UI_DESIGN.md 10.11). */
    val cleanedUrl: String,
    val title: String?,
    val description: String?,
    val imagePath: String?,
    val fetchedAt: Instant,
    val source: LinkPreviewSource,
)

/** Where a preview came from: sent with the message by the network, or fetched by the phone. */
@Serializable
enum class LinkPreviewSource {
    NETWORK,
    LOCAL,
}
