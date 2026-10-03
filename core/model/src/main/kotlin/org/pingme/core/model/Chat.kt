// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Chat(
    val id: ChatId,
    val accountId: AccountId,
    val kind: ChatKind,
    val title: String,
    val participants: List<PersonId>,
    val unreadCount: Int,
    val lastActivityAt: Instant,
    val isPinned: Boolean,
    /** Position in the pinned grid; null when not pinned. */
    val pinOrder: Int?,
    val isMuted: Boolean,
    /** When a timed mute ends; null for "forever" or when not muted. */
    val muteUntil: Instant?,
    val isArchived: Boolean,
    val isLowPriority: Boolean,
    val isObscured: Boolean,
    val folder: ChatFolder?,
    val spaceId: SpaceId?,
    /** Set when this chat is a member of a merged chat (UI_DESIGN.md 10.15). */
    val mergedInto: ChatId?,
    val avatarSource: AvatarSource,
    val nameOverride: String?,
    /** Merged chats: the service the composer starts on. */
    val defaultSendAccount: AccountId?,
    val networkRemoteId: String,
    /**
     * When the chat was last read on this phone: a network sync showing it unread again,
     * with nothing newer, is ignored (owner, Gate G7).
     */
    val readUpTo: Instant? = null,
)

@Serializable
enum class ChatKind {
    DIRECT,
    GROUP,
}

/** Instagram's Primary, General, and Requests folders, and Telegram forum topics. */
@Serializable
enum class ChatFolder {
    PRIMARY,
    GENERAL,
    REQUESTS,
    TOPIC,
}

/** Which picture represents a chat or person (UI_DESIGN.md 10.18). */
@Serializable
sealed interface AvatarSource {
    /** The Google Contacts (contacts provider) photo. The default. */
    @Serializable
    data object Contacts : AvatarSource

    /** The profile photo from one network account. */
    @Serializable
    data class Network(
        val accountId: AccountId,
    ) : AvatarSource

    /** A coloured tile with initials in the chosen shape. */
    @Serializable
    data object Initials : AvatarSource
}
