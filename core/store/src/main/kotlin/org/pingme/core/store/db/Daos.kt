// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Relation
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MediaSaveState
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Transport
import kotlin.time.Instant

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY displayName")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: String): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY displayName")
    suspend fun getAll(): List<AccountEntity>

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE accounts SET state = :state WHERE id = :id")
    suspend fun updateState(
        id: String,
        state: ConnectionState,
    )

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}

data class ChatWithParticipants(
    @Embedded val chat: ChatEntity,
    @Relation(parentColumn = "id", entityColumn = "chatId")
    val participants: List<ChatParticipantEntity>,
)

/** Unread messages in one space's hand-added chats, under the counting rule. */
data class SpaceUnreadRow(
    val spaceId: String,
    val unread: Int,
)

/** One row of the unread counting rule (see ChatDao.unreadRows). */
data class UnreadRow(
    val accountId: String,
    val network: NetworkId,
    val spaceId: String?,
    val unread: Int,
)

/** The newest message of one chat (see MessageDao.observeLastMessages). */
data class LastMessageRow(
    val chatId: String,
    val body: String?,
    val kind: MessageKind,
    val isOutgoing: Boolean,
    val transport: Transport,
    val sentAt: Instant,
    val senderName: String?,
    val status: MessageStatus,
)

@Dao
interface ChatDao {
    @Transaction
    @Query("SELECT * FROM chats ORDER BY lastActivityAt DESC")
    fun observeAll(): Flow<List<ChatWithParticipants>>

    /** Remembers that the user deleted a chat here, so a network listing does not bring it back. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun tombstone(tombstone: ChatTombstoneEntity)

    @Query("SELECT hiddenAt FROM chat_tombstones WHERE chatId = :chatId")
    suspend fun hiddenAt(chatId: String): Instant?

    @Query("DELETE FROM chat_tombstones WHERE chatId = :chatId")
    suspend fun unhide(chatId: String)

    @Transaction
    @Query("SELECT * FROM chats WHERE id = :id")
    fun observe(id: String): Flow<ChatWithParticipants?>

    @Transaction
    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun get(id: String): ChatWithParticipants?

    /**
     * The main inbox list: chats that are not archived, not low priority, not message
     * requests, not a hidden Instagram General chat, from accounts shown in the inbox
     * (UI_DESIGN.md 3.1, 6.4, 6.5, 10.7).
     */
    @Transaction
    @Query(
        """
        SELECT c.* FROM chats c JOIN accounts a ON a.id = c.accountId
        WHERE c.isArchived = 0 AND c.isLowPriority = 0
          AND (c.folder IS NULL OR c.folder != 'REQUESTS')
          AND (c.folder IS NULL OR c.folder != 'GENERAL' OR :showGeneral)
          AND a.showInInbox = 1
        ORDER BY c.lastActivityAt DESC
        """,
    )
    fun observeInbox(showGeneral: Boolean): Flow<List<ChatWithParticipants>>

    /** Pinned chats in grid order. */
    @Transaction
    @Query("SELECT * FROM chats WHERE isPinned = 1 ORDER BY pinOrder")
    suspend fun pinned(): List<ChatWithParticipants>

    @Transaction
    @Query("SELECT * FROM chats WHERE isArchived = 1 ORDER BY lastActivityAt DESC")
    fun observeArchived(): Flow<List<ChatWithParticipants>>

    @Transaction
    @Query("SELECT * FROM chats WHERE isLowPriority = 1 ORDER BY lastActivityAt DESC")
    fun observeLowPriority(): Flow<List<ChatWithParticipants>>

    @Transaction
    @Query("SELECT * FROM chats WHERE folder = :folder ORDER BY lastActivityAt DESC")
    fun observeFolder(folder: ChatFolder): Flow<List<ChatWithParticipants>>

    @Transaction
    @Query("SELECT * FROM chats WHERE accountId = :accountId ORDER BY lastActivityAt DESC")
    fun observeByAccount(accountId: String): Flow<List<ChatWithParticipants>>

    /**
     * The unread counting rule (BUILD_PLAN.md P1.2, UI_DESIGN.md 6.4), grouped so every badge
     * can be summed from it: only chats that are not archived, not low priority, not muted
     * (a timed mute counts until [now]), not message requests, not a hidden General chat,
     * in accounts shown in the inbox.
     */
    @Query(
        """
        SELECT c.accountId AS accountId, a.network AS network, c.spaceId AS spaceId,
               SUM(c.unreadCount) AS unread
        FROM chats c JOIN accounts a ON a.id = c.accountId
        WHERE c.unreadCount > 0
          AND c.isArchived = 0
          AND c.isLowPriority = 0
          AND NOT (c.isMuted = 1 AND (c.muteUntil IS NULL OR c.muteUntil > :now))
          AND (c.folder IS NULL OR c.folder != 'REQUESTS')
          AND (c.folder IS NULL OR c.folder != 'GENERAL' OR :showGeneral)
          AND a.showInInbox = 1
        GROUP BY c.accountId, c.spaceId
        """,
    )
    fun observeUnreadRows(
        now: Instant,
        showGeneral: Boolean,
    ): Flow<List<UnreadRow>>

    /** The same rule for chats added to a space by hand (a space the user made, UI_DESIGN.md 10.4). */
    @Query(
        """
        SELECT sc.spaceId AS spaceId, SUM(c.unreadCount) AS unread
        FROM space_chats sc JOIN chats c ON c.id = sc.chatId JOIN accounts a ON a.id = c.accountId
        WHERE c.unreadCount > 0
          AND c.isArchived = 0
          AND c.isLowPriority = 0
          AND NOT (c.isMuted = 1 AND (c.muteUntil IS NULL OR c.muteUntil > :now))
          AND (c.folder IS NULL OR c.folder != 'REQUESTS')
          AND (c.folder IS NULL OR c.folder != 'GENERAL' OR :showGeneral)
          AND a.showInInbox = 1
        GROUP BY sc.spaceId
        """,
    )
    fun observeSpaceMemberUnread(
        now: Instant,
        showGeneral: Boolean,
    ): Flow<List<SpaceUnreadRow>>

    @Upsert
    suspend fun upsertChat(chat: ChatEntity)

    @Query("DELETE FROM chat_participants WHERE chatId = :chatId")
    suspend fun deleteParticipants(chatId: String)

    @Insert
    suspend fun insertParticipants(participants: List<ChatParticipantEntity>)

    @Transaction
    suspend fun upsert(
        chat: ChatEntity,
        participants: List<ChatParticipantEntity>,
    ) {
        upsertChat(chat)
        deleteParticipants(chat.id)
        insertParticipants(participants)
    }

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)
}

data class MessageWithParts(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<AttachmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val reactions: List<ReactionEntity>,
)

@Dao
interface MessageDao {
    /** The newest [limit] messages of a chat, newest first. */
    @Transaction
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY sentAt DESC, rowId DESC LIMIT :limit")
    fun observeLatest(
        chatId: String,
        limit: Int,
    ): Flow<List<MessageWithParts>>

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id")
    fun observe(id: String): Flow<MessageWithParts?>

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageWithParts?

    @Transaction
    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<MessageWithParts>

    @Query("SELECT id FROM messages WHERE chatId = :chatId ORDER BY sentAt ASC, rowId ASC LIMIT 1")
    suspend fun oldestId(chatId: String): String?

    @Query("SELECT id FROM messages WHERE chatId = :chatId ORDER BY sentAt DESC, rowId DESC LIMIT 1")
    suspend fun newestId(chatId: String): String?

    /** The newest message of every chat, for inbox previews (UI_DESIGN.md 3.1). */
    @Query(
        """
        SELECT m.chatId AS chatId, m.body AS body, m.kind AS kind, m.isOutgoing AS isOutgoing,
               m.transport AS transport, m.sentAt AS sentAt, p.displayName AS senderName,
               m.status AS status
        FROM messages m LEFT JOIN persons p ON p.id = m.senderId
        WHERE m.rowId = (
            SELECT x.rowId FROM messages x WHERE x.chatId = m.chatId
            ORDER BY x.sentAt DESC, x.rowId DESC LIMIT 1
        )
        """,
    )
    fun observeLastMessages(): Flow<List<LastMessageRow>>

    /** Pinned messages of a chat, newest pin first (UI_DESIGN.md 5.1). */
    @Transaction
    @Query(
        """
        SELECT m.* FROM messages m JOIN pinned_messages p ON p.messageId = m.id
        WHERE p.chatId = :chatId ORDER BY p.pinnedAt DESC, p.rowid DESC
        """,
    )
    fun observePinned(chatId: String): Flow<List<MessageWithParts>>

    @Upsert
    suspend fun pin(pin: PinnedMessageEntity)

    @Query("DELETE FROM pinned_messages WHERE messageId = :messageId")
    suspend fun unpin(messageId: String)

    /** A chat's messages of the given kinds (by enum name), newest first: search in chat's media chips. */
    @Transaction
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND kind IN (:kinds) " +
            "ORDER BY sentAt DESC, rowId DESC LIMIT :limit",
    )
    fun observeKinds(
        chatId: String,
        kinds: List<String>,
        limit: Int,
    ): Flow<List<MessageWithParts>>

    /** Everything one person sent in a chat, newest first: search in chat's sender filter. */
    @Transaction
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND senderId = :senderId " +
            "ORDER BY sentAt DESC, rowId DESC LIMIT :limit",
    )
    fun observeFrom(
        chatId: String,
        senderId: String,
        limit: Int,
    ): Flow<List<MessageWithParts>>

    /** A chat's messages with a link in them or a preview card, newest first. */
    @Transaction
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND (preview_url IS NOT NULL " +
            "OR body LIKE '%http://%' OR body LIKE '%https://%') ORDER BY sentAt DESC, rowId DESC LIMIT :limit",
    )
    fun observeLinks(
        chatId: String,
        limit: Int,
    ): Flow<List<MessageWithParts>>

    /** How many of a chat's messages are newer than [sentAt]: how far back the list must reach to show one. */
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND sentAt > :sentAt")
    suspend fun countNewer(
        chatId: String,
        sentAt: Instant,
    ): Int

    /** How many messages from other people are newer than [sentAt]: the chat's unread count as PingMe sees it. */
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND isOutgoing = 0 AND sentAt > :sentAt")
    suspend fun countIncomingNewer(
        chatId: String,
        sentAt: Instant,
    ): Int

    /** Whether the chat's newest message is one of ours. */
    @Query("SELECT isOutgoing FROM messages WHERE chatId = :chatId ORDER BY sentAt DESC, rowId DESC LIMIT 1")
    suspend fun newestIsOutgoing(chatId: String): Boolean?

    /** The first message on or after [from], for search in chat's date jump. */
    @Query("SELECT id FROM messages WHERE chatId = :chatId AND sentAt >= :from ORDER BY sentAt ASC, rowId ASC LIMIT 1")
    suspend fun firstFrom(
        chatId: String,
        from: Instant,
    ): String?

    /** Who "you" are in a chat: the sender of any of your own messages there. */
    @Query("SELECT senderId FROM messages WHERE chatId = :chatId AND isOutgoing = 1 LIMIT 1")
    suspend fun selfSenderId(chatId: String): String?

    @Query("SELECT rowId FROM messages WHERE id = :id")
    suspend fun rowIdFor(id: String): Long?

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteAttachments(messageId: String)

    @Insert
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM reactions WHERE messageId = :messageId")
    suspend fun deleteReactions(messageId: String)

    @Insert
    suspend fun insertReactions(reactions: List<ReactionEntity>)

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    suspend fun attachmentsOf(messageId: String): List<AttachmentEntity>

    /**
     * Inserts or updates a message with its attachments and reactions. Updates keep the row
     * (and its rowId) in place instead of replacing it, so nothing cascades by accident, and
     * keep each attachment's downloaded file and a link preview fetched on the phone: a
     * network's updated copy of a message (a reaction, a status, a history re-fetch) never
     * carries the file or the preview PingMe already saved.
     */
    @Transaction
    suspend fun upsert(
        message: MessageEntity,
        attachments: List<AttachmentEntity>,
        reactions: List<ReactionEntity>,
    ) {
        val existing = get(message.id)?.message
        val merged = if (message.linkPreview == null) message.copy(linkPreview = existing?.linkPreview) else message
        if (existing == null) {
            insertMessage(merged.copy(rowId = 0))
        } else {
            updateMessage(merged.copy(rowId = existing.rowId))
        }
        // A downloaded file stays known, unless the network now names a different file for
        // the same part: the full-size picture after its thumbnail (owner, Gate G3: blurry
        // pictures over RCS), which must be fetched again.
        val kept = attachmentsOf(message.id).filter { it.localPath != null }.associateBy { it.id }
        deleteAttachments(message.id)
        insertAttachments(
            attachments.map { fresh ->
                val before = kept[fresh.id]
                val sameFile = before != null && (fresh.remoteRef == null || fresh.remoteRef == before.remoteRef)
                if (fresh.localPath == null && sameFile) fresh.copy(localPath = before?.localPath) else fresh
            },
        )
        deleteReactions(message.id)
        insertReactions(reactions)
    }

    /** Stand-in copies of sent messages (ids with [prefix]) older than [before]: never resolved, so junk. */
    @Query("DELETE FROM messages WHERE networkRemoteId LIKE :prefix || '%' AND sentAt < :before")
    suspend fun deleteStandIns(
        prefix: String,
        before: Instant,
    ): Int

    /** Stand-in copies of sent messages still shown in a chat. */
    @Transaction
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND networkRemoteId LIKE :prefix || '%'")
    suspend fun standIns(
        chatId: String,
        prefix: String,
    ): List<MessageWithParts>

    /** The newest [limit] messages of a chat sent at or before [before], newest first. */
    @Transaction
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND sentAt <= :before " +
            "ORDER BY sentAt DESC, rowId DESC LIMIT :limit",
    )
    suspend fun before(
        chatId: String,
        before: Instant,
        limit: Int,
    ): List<MessageWithParts>

    @Query("SELECT messageId FROM attachments WHERE id = :id")
    suspend fun messageOfAttachment(id: String): String?

    /** Text messages with nothing to show (no body, no attachment): empty bubbles. */
    @Query(
        "DELETE FROM messages WHERE kind = 'TEXT' AND (body IS NULL OR body = '') AND deletedForEveryone = 0 " +
            "AND id NOT IN (SELECT messageId FROM attachments)",
    )
    suspend fun deleteEmpty(): Int

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: MessageStatus,
    )

    /**
     * A read receipt: every outgoing message in the chat sent up to and including [upToId]
     * becomes Read. Messages already Read or still sending are left alone.
     */
    @Query(
        """
        UPDATE messages SET status = :read
        WHERE chatId = :chatId AND isOutgoing = 1
          AND sentAt <= (SELECT sentAt FROM messages WHERE id = :upToId)
          AND status IN (:sent, :delivered)
        """,
    )
    suspend fun markOutgoingRead(
        chatId: String,
        upToId: String,
        read: MessageStatus = MessageStatus.Read,
        sent: MessageStatus = MessageStatus.Sent,
        delivered: MessageStatus = MessageStatus.Delivered,
    )

    @Query("UPDATE attachments SET localPath = :localPath WHERE id = :id")
    suspend fun setAttachmentLocalPath(
        id: String,
        localPath: String,
    )

    @Query("UPDATE attachments SET savedAt = :at WHERE id = :id")
    suspend fun setAttachmentSavedAt(
        id: String,
        at: kotlin.time.Instant,
    )

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun attachment(id: String): AttachmentEntity?

    @Upsert
    suspend fun upsertReaction(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND senderId = :senderId")
    suspend fun deleteReactionsBy(
        messageId: String,
        senderId: String,
    )

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND senderId = :senderId AND emoji = :emoji")
    suspend fun deleteReaction(
        messageId: String,
        senderId: String,
        emoji: String,
    )

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    /** Full-text search. Build the query with [searchQuery]; results are message IDs, best first. */
    @RawQuery(observedEntities = [MessageEntity::class, AttachmentEntity::class, PersonEntity::class])
    fun observeSearchIds(query: RoomRawQuery): Flow<List<String>>

    companion object {
        /** Messages matching [match] (an FTS5 expression), newest first, optionally in one chat. */
        fun searchQuery(
            match: String,
            chatId: String?,
            limit: Int,
        ): RoomRawQuery {
            val inChat = if (chatId != null) "AND m.chatId = ?" else ""
            val sql =
                "SELECT m.id FROM ${MessageFts.TABLE} f JOIN messages m ON m.rowId = f.rowid " +
                    "WHERE ${MessageFts.TABLE} MATCH ? $inChat ORDER BY m.sentAt DESC LIMIT ?"
            return RoomRawQuery(sql) { statement ->
                var index = 1
                statement.bindText(index++, match)
                if (chatId != null) statement.bindText(index++, chatId)
                statement.bindLong(index, limit.toLong())
            }
        }
    }
}

@Dao
interface PersonDao {
    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun get(id: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE id = :id")
    fun observe(id: String): Flow<PersonEntity?>

    @Query("SELECT * FROM persons WHERE accountId = :accountId ORDER BY displayName")
    fun observeByAccount(accountId: String): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE phoneNumber = :phoneNumber")
    suspend fun byPhoneNumber(phoneNumber: String): List<PersonEntity>

    @Query("SELECT * FROM persons WHERE contactId = :contactId")
    fun observeByContact(contactId: String): Flow<List<PersonEntity>>

    @Upsert
    suspend fun upsert(person: PersonEntity)

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun delete(id: String)

    /** Drops an account's people whose handle ends with [suffix] and whom no chat lists (stale hidden ids). */
    @Query(
        "DELETE FROM persons WHERE accountId = :accountId AND networkHandle LIKE '%' || :suffix " +
            "AND id NOT IN (SELECT personId FROM chat_participants)",
    )
    suspend fun deleteStray(
        accountId: String,
        suffix: String,
    )

    /** Drops an account's person with exactly this handle, when no chat lists them. */
    @Query(
        "DELETE FROM persons WHERE accountId = :accountId AND networkHandle = :handle " +
            "AND id NOT IN (SELECT personId FROM chat_participants)",
    )
    suspend fun deleteStrayHandle(
        accountId: String,
        handle: String,
    )
}

data class SpaceWithChats(
    @Embedded val space: SpaceEntity,
    @Relation(parentColumn = "id", entityColumn = "spaceId")
    val chats: List<SpaceChatEntity>,
)

@Dao
interface SpaceDao {
    @Transaction
    @Query("SELECT * FROM spaces ORDER BY title")
    fun observeAll(): Flow<List<SpaceWithChats>>

    @Transaction
    @Query("SELECT * FROM spaces WHERE id = :id")
    suspend fun get(id: String): SpaceWithChats?

    @Upsert
    suspend fun upsertSpace(space: SpaceEntity)

    @Query("DELETE FROM space_chats WHERE spaceId = :spaceId")
    suspend fun deleteChats(spaceId: String)

    @Insert
    suspend fun insertChats(chats: List<SpaceChatEntity>)

    @Transaction
    suspend fun upsert(
        space: SpaceEntity,
        chats: List<SpaceChatEntity>,
    ) {
        upsertSpace(space)
        deleteChats(space.id)
        insertChats(chats)
    }

    @Query("DELETE FROM spaces WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScheduledSendDao {
    @Query("SELECT * FROM scheduled_sends ORDER BY sendAt")
    fun observeAll(): Flow<List<ScheduledSendEntity>>

    @Query("SELECT * FROM scheduled_sends WHERE chatId = :chatId ORDER BY sendAt")
    fun observeByChat(chatId: String): Flow<List<ScheduledSendEntity>>

    /** Sends whose time has come, including late ones (UI_DESIGN.md 10.13). */
    @Query("SELECT * FROM scheduled_sends WHERE sendAt <= :now ORDER BY sendAt")
    suspend fun due(now: Instant): List<ScheduledSendEntity>

    @Query("SELECT * FROM scheduled_sends WHERE messageId = :messageId")
    suspend fun get(messageId: String): ScheduledSendEntity?

    @Upsert
    suspend fun upsert(send: ScheduledSendEntity)

    @Query("UPDATE scheduled_sends SET attempts = attempts + 1 WHERE messageId = :messageId")
    suspend fun incrementAttempts(messageId: String)

    @Query("DELETE FROM scheduled_sends WHERE messageId = :messageId")
    suspend fun delete(messageId: String)
}

@Dao
interface KeywordRuleDao {
    @Query("SELECT * FROM keyword_rules ORDER BY pattern")
    fun observeAll(): Flow<List<KeywordRuleEntity>>

    @Upsert
    suspend fun upsert(rule: KeywordRuleEntity)

    @Query("DELETE FROM keyword_rules WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MergeLinkDao {
    @Query("SELECT * FROM merge_links ORDER BY confirmedAt")
    fun observeAll(): Flow<List<MergeLinkEntity>>

    @Query("SELECT * FROM merge_links WHERE contactId = :contactId")
    suspend fun forContact(contactId: String): List<MergeLinkEntity>

    @Query("SELECT * FROM merge_links WHERE personId = :personId")
    suspend fun forPerson(personId: String): List<MergeLinkEntity>

    @Upsert
    suspend fun upsert(link: MergeLinkEntity)

    @Query("DELETE FROM merge_links WHERE personId = :personId AND contactId = :contactId")
    suspend fun delete(
        personId: String,
        contactId: String,
    )
}

@Dao
interface MediaSaveJobDao {
    @Query("SELECT * FROM media_save_jobs WHERE state = :state")
    fun observeByState(state: MediaSaveState): Flow<List<MediaSaveJobEntity>>

    @Query("SELECT * FROM media_save_jobs WHERE attachmentId = :attachmentId")
    suspend fun get(attachmentId: String): MediaSaveJobEntity?

    @Upsert
    suspend fun upsert(job: MediaSaveJobEntity)

    @Query("DELETE FROM media_save_jobs WHERE attachmentId = :attachmentId")
    suspend fun delete(attachmentId: String)
}

@Dao
interface ChatOverridesDao {
    @Query("SELECT * FROM chat_overrides WHERE chatId = :chatId")
    fun observe(chatId: String): Flow<ChatOverridesEntity?>

    @Query("SELECT * FROM chat_overrides WHERE chatId = :chatId")
    suspend fun get(chatId: String): ChatOverridesEntity?

    @Upsert
    suspend fun upsert(overrides: ChatOverridesEntity)
}
