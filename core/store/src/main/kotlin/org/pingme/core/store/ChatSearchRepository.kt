// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.PersonId
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Search in one chat beyond full text (UI_DESIGN.md 10.14): its media by type, its links,
 * and finding where a message or a date sits in the history. Full-text search is
 * [MessageRepository.search].
 */
@Singleton
class ChatSearchRepository
    @Inject
    constructor(
        db: PingMeDatabase,
    ) {
        private val dao = db.messageDao()

        /** A chat's photos, videos, files, voice notes, or GIFs, newest first. */
        fun ofKinds(
            chatId: ChatId,
            kinds: Set<MessageKind>,
            limit: Int = LIMIT,
        ): Flow<List<Message>> =
            dao.observeKinds(chatId.value, kinds.map { it.name }, limit).map { rows -> rows.map { it.toModel() } }

        /** Everything [sender] sent in a chat, newest first. */
        fun from(
            chatId: ChatId,
            sender: PersonId,
            limit: Int = LIMIT,
        ): Flow<List<Message>> =
            dao.observeFrom(chatId.value, sender.value, limit).map { rows ->
                rows.map { it.toModel() }
            }

        /** A chat's messages that carry links, newest first. */
        fun withLinks(
            chatId: ChatId,
            limit: Int = LIMIT,
        ): Flow<List<Message>> = dao.observeLinks(chatId.value, limit).map { rows -> rows.map { it.toModel() } }

        /** How many messages in [message]'s chat are newer than it. */
        suspend fun countNewer(message: Message): Int = dao.countNewer(message.chatId.value, message.sentAt)

        /** The same across several chats: a merged chat's one timeline. */
        suspend fun countNewerIn(
            chatIds: List<ChatId>,
            sentAt: kotlin.time.Instant,
        ): Int = dao.countNewerIn(chatIds.map { it.value }, sentAt)

        /** The first message in a chat on or after [from]. */
        suspend fun firstFrom(
            chatId: ChatId,
            from: Instant,
        ): MessageId? = dao.firstFrom(chatId.value, from)?.let(::MessageId)

        private companion object {
            const val LIMIT = 300
        }
    }
