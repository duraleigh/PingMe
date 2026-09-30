// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.pingme.core.model.AccountId
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceId
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.chatEntities
import org.pingme.core.store.db.participantEntities
import org.pingme.core.store.db.toEntity
import org.pingme.core.store.db.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/** Unread totals under the one counting rule. Every badge in the app reads from these. */
data class UnreadTotals(
    val total: Int,
    val byAccount: Map<AccountId, Int>,
    val byNetwork: Map<NetworkId, Int>,
    val bySpace: Map<SpaceId, Int>,
) {
    companion object {
        val NONE = UnreadTotals(0, emptyMap(), emptyMap(), emptyMap())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ChatRepository
    @Inject
    constructor(
        private val db: PingMeDatabase,
        private val settings: SettingsRepository,
        private val clock: Clock,
    ) {
        private val dao = db.chatDao()
        private val spaceDao = db.spaceDao()

        /** The main inbox list, newest activity first (UI_DESIGN.md 3.1). */
        fun inbox(): Flow<List<Chat>> =
            settings.instagramShowGeneral
                .flatMapLatest { showGeneral -> dao.observeInbox(showGeneral) }
                .map { rows -> rows.map { it.toModel() } }

        fun all(): Flow<List<Chat>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

        fun archived(): Flow<List<Chat>> = dao.observeArchived().map { rows -> rows.map { it.toModel() } }

        fun lowPriority(): Flow<List<Chat>> = dao.observeLowPriority().map { rows -> rows.map { it.toModel() } }

        /** Chats in one folder, such as Instagram Requests or General (UI_DESIGN.md 6.4). */
        fun folder(folder: ChatFolder): Flow<List<Chat>> =
            dao.observeFolder(folder).map { rows -> rows.map { it.toModel() } }

        fun byAccount(accountId: AccountId): Flow<List<Chat>> =
            dao.observeByAccount(accountId.value).map { rows -> rows.map { it.toModel() } }

        fun chat(id: ChatId): Flow<Chat?> = dao.observe(id.value).map { it?.toModel() }

        suspend fun get(id: ChatId): Chat? = dao.get(id.value)?.toModel()

        suspend fun upsert(chat: Chat) = dao.upsert(chat.toEntity(), chat.participantEntities())

        /** Reads, changes, and writes one chat atomically (pin, mute, archive, ...). */
        suspend fun update(
            id: ChatId,
            transform: (Chat) -> Chat,
        ): Chat? =
            db.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    dao
                        .get(id.value)
                        ?.toModel()
                        ?.let(transform)
                        ?.also { upsert(it) }
                }
            }

        suspend fun delete(id: ChatId) = dao.delete(id.value)

        /**
         * The unread counting rule, implemented once (BUILD_PLAN.md P1.2): only chats that are
         * not archived, not low priority, not muted, not message requests, not a hidden
         * Instagram General chat, and in accounts shown in the inbox. A timed mute stops
         * hiding a chat once it ends; the totals refresh on the next database change.
         */
        fun unreadTotals(): Flow<UnreadTotals> =
            settings.instagramShowGeneral
                .flatMapLatest { showGeneral -> dao.observeUnreadRows(clock.now(), showGeneral) }
                .map { rows ->
                    UnreadTotals(
                        total = rows.sumOf { it.unread },
                        byAccount =
                            rows.groupBy { AccountId(it.accountId) }.mapValues { (_, r) ->
                                r.sumOf { it.unread }
                            },
                        byNetwork = rows.groupBy { it.network }.mapValues { (_, r) -> r.sumOf { it.unread } },
                        bySpace =
                            rows
                                .filter { it.spaceId != null }
                                .groupBy { SpaceId(it.spaceId!!) }
                                .mapValues { (_, r) -> r.sumOf { it.unread } },
                    )
                }

        fun spaces(): Flow<List<Space>> = spaceDao.observeAll().map { rows -> rows.map { it.toModel() } }

        suspend fun space(id: SpaceId): Space? = spaceDao.get(id.value)?.toModel()

        suspend fun upsertSpace(space: Space) = spaceDao.upsert(space.toEntity(), space.chatEntities())

        suspend fun deleteSpace(id: SpaceId) = spaceDao.delete(id.value)
    }
