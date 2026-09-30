// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pingme.core.model.ChatId
import org.pingme.core.model.MessageId
import org.pingme.core.model.ScheduledSend
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.toEntity
import org.pingme.core.store.db.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/** Send-later messages waiting for their time (UI_DESIGN.md 10.13). */
@Singleton
class ScheduledSendRepository
    @Inject
    constructor(
        db: PingMeDatabase,
    ) {
        private val dao = db.scheduledSendDao()

        fun all(): Flow<List<ScheduledSend>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

        fun inChat(chatId: ChatId): Flow<List<ScheduledSend>> =
            dao.observeByChat(chatId.value).map { rows ->
                rows.map {
                    it.toModel()
                }
            }

        /** Sends whose time has come, including late ones. */
        suspend fun due(now: Instant): List<ScheduledSend> = dao.due(now).map { it.toModel() }

        suspend fun upsert(send: ScheduledSend) = dao.upsert(send.toEntity())

        suspend fun recordAttempt(messageId: MessageId) = dao.incrementAttempts(messageId.value)

        suspend fun delete(messageId: MessageId) = dao.delete(messageId.value)
    }
