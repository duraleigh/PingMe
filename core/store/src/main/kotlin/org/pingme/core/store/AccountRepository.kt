// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.toEntity
import org.pingme.core.store.db.toModel
import javax.inject.Inject
import javax.inject.Singleton

// The repositories are the only thing the UI and the service talk to (BUILD_PLAN.md P1.2).

@Singleton
class AccountRepository
    @Inject
    constructor(
        private val db: PingMeDatabase,
    ) {
        private val dao = db.accountDao()

        fun accounts(): Flow<List<Account>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

        fun account(id: AccountId): Flow<Account?> = dao.observe(id.value).map { it?.toModel() }

        suspend fun get(id: AccountId): Account? = dao.get(id.value)?.toModel()

        suspend fun getAll(): List<Account> = dao.getAll().map { it.toModel() }

        suspend fun upsert(account: Account) = dao.upsert(account.toEntity())

        suspend fun updateState(
            id: AccountId,
            state: ConnectionState,
        ) = dao.updateState(id.value, state)

        /** Removes the account and, by cascade, its chats, messages, and people. */
        suspend fun delete(id: AccountId) = dao.delete(id.value)
    }
