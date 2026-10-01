// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.KeywordScope
import org.pingme.core.model.LinkPreviewSource
import org.pingme.core.model.MediaSaveState
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.SpaceKind
import org.pingme.core.model.Transport
import kotlin.time.Instant

// Tables mirror core/model (BUILD_PLAN.md P1.2). Sealed types are stored as JSON
// (see Converters); lists become child tables.

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val network: NetworkId,
    val displayName: String,
    val colorArgb: Int,
    val state: ConnectionState,
    val showInInbox: Boolean,
    val notificationMode: NotificationMode,
    val credentialRef: String,
)

@Entity(
    tableName = "chats",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId"), Index("lastActivityAt"), Index("spaceId"), Index("mergedInto")],
)
data class ChatEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val kind: ChatKind,
    val title: String,
    val unreadCount: Int,
    val lastActivityAt: Instant,
    val isPinned: Boolean,
    val pinOrder: Int?,
    val isMuted: Boolean,
    val muteUntil: Instant?,
    val isArchived: Boolean,
    val isLowPriority: Boolean,
    val isObscured: Boolean,
    val folder: ChatFolder?,
    val spaceId: String?,
    val mergedInto: String?,
    val avatarSource: AvatarSource,
    val nameOverride: String?,
    val defaultSendAccount: String?,
    val networkRemoteId: String,
)

@Entity(
    tableName = "chat_participants",
    primaryKeys = ["chatId", "personId"],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("personId")],
)
data class ChatParticipantEntity(
    val chatId: String,
    val personId: String,
    /** Keeps the participant order the connector reported. */
    val position: Int,
)

/**
 * [rowId] is an internal integer key. The FTS5 index (MessageFts) is keyed by it, and an
 * INTEGER PRIMARY KEY never changes, unlike SQLite's implicit rowid on a VACUUM.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["id"], unique = true), Index("chatId", "sentAt"), Index("senderId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val id: String,
    val chatId: String,
    val senderId: String,
    val sentAt: Instant,
    val receivedAt: Instant,
    val body: String?,
    val kind: MessageKind,
    val replyTo: String?,
    @Embedded(prefix = "quote_") val quote: QuoteColumns?,
    val editedAt: Instant?,
    val deletedForEveryone: Boolean,
    val status: MessageStatus,
    val transport: Transport,
    val networkRemoteId: String,
    @Embedded(prefix = "preview_") val linkPreview: LinkPreviewColumns?,
    val isOutgoing: Boolean,
)

data class QuoteColumns(
    val senderName: String,
    val text: String,
)

data class LinkPreviewColumns(
    val url: String,
    val cleanedUrl: String,
    val title: String?,
    val description: String?,
    val imagePath: String?,
    val fetchedAt: Instant,
    val source: LinkPreviewSource,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val position: Int,
    val kind: AttachmentKind,
    val mimeType: String,
    val fileName: String?,
    val sizeBytes: Long,
    val localPath: String?,
    val remoteRef: String?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val isEphemeral: Boolean,
    val savedAt: Instant?,
)

@Entity(
    tableName = "reactions",
    primaryKeys = ["messageId", "senderId", "emoji"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReactionEntity(
    val messageId: String,
    val senderId: String,
    val emoji: String,
    val at: Instant,
)

/**
 * A message pinned in its chat (UI_DESIGN.md 5.1): local, so it works on every network.
 * The banner under the chat header shows the newest pin. Schema version 2.
 */
@Entity(
    tableName = "pinned_messages",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId")],
)
data class PinnedMessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val pinnedAt: Instant,
)

@Entity(
    tableName = "persons",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId"), Index("phoneNumber"), Index("contactId")],
)
data class PersonEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val displayName: String,
    val phoneNumber: String?,
    val networkHandle: String,
    val avatarPath: String?,
    val contactId: String?,
)

@Entity(tableName = "spaces", indices = [Index("accountId")])
data class SpaceEntity(
    @PrimaryKey val id: String,
    val accountId: String?,
    val title: String,
    val kind: SpaceKind,
)

@Entity(
    tableName = "space_chats",
    primaryKeys = ["spaceId", "chatId"],
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId")],
)
data class SpaceChatEntity(
    val spaceId: String,
    val chatId: String,
    val position: Int,
)

@Entity(tableName = "scheduled_sends", indices = [Index("sendAt"), Index("chatId")])
data class ScheduledSendEntity(
    @PrimaryKey val messageId: String,
    val sendAt: Instant,
    val accountId: String,
    val chatId: String,
    val payloadJson: String,
    val attempts: Int,
)

@Entity(tableName = "keyword_rules")
data class KeywordRuleEntity(
    @PrimaryKey val id: String,
    val pattern: String,
    val wholeWord: Boolean,
    val caseSensitive: Boolean,
    val scope: KeywordScope,
    val channelId: String,
    val overridesSilence: Boolean,
)

@Entity(tableName = "merge_links", primaryKeys = ["personId", "contactId"], indices = [Index("contactId")])
data class MergeLinkEntity(
    val personId: String,
    val contactId: String,
    val confirmedAt: Instant,
)

@Entity(tableName = "media_save_jobs", indices = [Index("state")])
data class MediaSaveJobEntity(
    @PrimaryKey val attachmentId: String,
    val state: MediaSaveState,
)
