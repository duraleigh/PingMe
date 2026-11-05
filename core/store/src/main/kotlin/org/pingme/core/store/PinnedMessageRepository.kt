// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.PinnedMessageEntity
import org.pingme.core.store.db.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Pinned messages (UI_DESIGN.md 5.1): local to the phone, so pinning works on every network.
 * Deleting a message removes its pin with it.
 */
@Singleton
class PinnedMessageRepository
    @Inject
    constructor(
        db: PingMeDatabase,
    ) {
        private val dao = db.messageDao()

        /** Newest pin first. */
        fun pinned(chatId: ChatId): Flow<List<Message>> =
            dao.observePinned(chatId.value).map { rows -> rows.map { it.toModel() } }

        suspend fun pin(
            message: Message,
            at: Instant,
        ) = dao.pin(PinnedMessageEntity(message.id.value, message.chatId.value, at))

        /** Pinned messages across a merged chat's members. */
        fun pinnedIn(chatIds: List<ChatId>): Flow<List<Message>> =
            dao.observePinnedIn(chatIds.map { it.value }).map { rows -> rows.map { it.toModel() } }

        suspend fun unpin(id: MessageId) = dao.unpin(id.value)
    }
