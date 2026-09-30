// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.pingme.core.model.ChatId
import org.pingme.core.model.MediaSaveJob
import org.pingme.core.model.MediaSaveState
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.PersonId
import org.pingme.core.model.Reaction
import org.pingme.core.model.ScheduledSend
import org.pingme.core.store.db.MessageDao
import org.pingme.core.store.db.MessageFts
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.ReactionEntity
import org.pingme.core.store.db.attachmentEntities
import org.pingme.core.store.db.reactionEntities
import org.pingme.core.store.db.toEntity
import org.pingme.core.store.db.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MessageRepository
    @Inject
    constructor(
        db: PingMeDatabase,
    ) {
        private val dao = db.messageDao()
        private val scheduledDao = db.scheduledSendDao()
        private val mediaSaveDao = db.mediaSaveJobDao()

        /** The newest [limit] messages of a chat, newest first. */
        fun latest(
            chatId: ChatId,
            limit: Int,
        ): Flow<List<Message>> = dao.observeLatest(chatId.value, limit).map { rows -> rows.map { it.toModel() } }

        fun message(id: MessageId): Flow<Message?> = dao.observe(id.value).map { it?.toModel() }

        suspend fun get(id: MessageId): Message? = dao.get(id.value)?.toModel()

        suspend fun upsert(message: Message) =
            dao.upsert(message.toEntity(), message.attachmentEntities(), message.reactionEntities())

        suspend fun updateStatus(
            id: MessageId,
            status: MessageStatus,
        ) = dao.updateStatus(id.value, status)

        suspend fun addReaction(
            messageId: MessageId,
            reaction: Reaction,
        ) = dao.upsertReaction(ReactionEntity(messageId.value, reaction.senderId.value, reaction.emoji, reaction.at))

        suspend fun removeReaction(
            messageId: MessageId,
            senderId: PersonId,
            emoji: String,
        ) = dao.deleteReaction(messageId.value, senderId.value, emoji)

        suspend fun delete(id: MessageId) = dao.delete(id.value)

        /**
         * Full-text search over message text, sender names, and attachment names, newest
         * first. With [chatId] it searches one chat (UI_DESIGN.md 10.14).
         */
        fun search(
            text: String,
            chatId: ChatId? = null,
            limit: Int = SEARCH_LIMIT,
        ): Flow<List<Message>> {
            val match = MessageFts.matchExpression(text) ?: return flowOf(emptyList())
            return dao
                .observeSearchIds(MessageDao.searchQuery(match, chatId?.value, limit))
                .map { ids ->
                    val byId = dao.getAll(ids).associateBy { it.message.id }
                    ids.mapNotNull { byId[it]?.toModel() }
                }
        }

        fun scheduled(): Flow<List<ScheduledSend>> = scheduledDao.observeAll().map { rows -> rows.map { it.toModel() } }

        fun scheduledIn(chatId: ChatId): Flow<List<ScheduledSend>> =
            scheduledDao.observeByChat(chatId.value).map { rows -> rows.map { it.toModel() } }

        suspend fun dueScheduled(now: Instant): List<ScheduledSend> = scheduledDao.due(now).map { it.toModel() }

        suspend fun upsertScheduled(send: ScheduledSend) = scheduledDao.upsert(send.toEntity())

        suspend fun recordScheduledAttempt(messageId: MessageId) = scheduledDao.incrementAttempts(messageId.value)

        suspend fun deleteScheduled(messageId: MessageId) = scheduledDao.delete(messageId.value)

        fun mediaSaveJobs(state: MediaSaveState): Flow<List<MediaSaveJob>> =
            mediaSaveDao.observeByState(state).map { rows -> rows.map { it.toModel() } }

        suspend fun upsertMediaSaveJob(job: MediaSaveJob) = mediaSaveDao.upsert(job.toEntity())

        private companion object {
            const val SEARCH_LIMIT = 200
        }
    }
