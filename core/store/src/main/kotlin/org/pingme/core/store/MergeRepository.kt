// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.toModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merged chats (UI_DESIGN.md 10.15): a merged chat is a chat row of its own whose members
 * point at it through `mergedInto`. Members leave the inbox; the merged row carries their
 * summed unread count and newest activity, kept in step by ChatRepository on every write.
 */
@Singleton
class MergeRepository
    @Inject
    constructor(
        db: PingMeDatabase,
    ) {
        private val dao = db.chatDao()

        fun observeMembers(id: ChatId): Flow<List<Chat>> =
            dao.observeMembers(id.value).map { rows -> rows.map { it.toModel() } }

        suspend fun members(id: ChatId): List<Chat> = dao.members(id.value).map { it.toModel() }

        /** Every member chat with the merged chat it belongs to. */
        fun memberships(): Flow<Map<ChatId, ChatId>> =
            dao.observeMemberships().map { rows -> rows.associate { ChatId(it.id) to ChatId(it.mergedInto) } }

        /** Puts [member] into the merged chat [parent], or takes it out with null. */
        suspend fun setMergedInto(
            member: ChatId,
            parent: ChatId?,
        ) {
            val before = dao.get(member.value)?.chat?.mergedInto
            dao.setMergedInto(member.value, parent?.value)
            parent?.let { dao.refreshMerged(it.value) }
            before?.takeIf { it != parent?.value }?.let { tidy(ChatId(it)) }
        }

        /** A merged chat with fewer than two members releases the last one and goes. */
        suspend fun tidy(id: ChatId) {
            dao.releaseLonelyMember(id.value)
            dao.deleteIfNoMembers(id.value)
            dao.refreshMerged(id.value)
        }
    }
