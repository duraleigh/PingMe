// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pingme.core.model.AccountId
import org.pingme.core.model.ContactId
import org.pingme.core.model.MergeLink
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.store.db.PingMeDatabase
import org.pingme.core.store.db.toEntity
import org.pingme.core.store.db.toModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository
    @Inject
    constructor(
        db: PingMeDatabase,
    ) {
        private val personDao = db.personDao()
        private val mergeLinkDao = db.mergeLinkDao()

        suspend fun person(id: PersonId): Person? = personDao.get(id.value)?.toModel()

        fun observePerson(id: PersonId): Flow<Person?> = personDao.observe(id.value).map { it?.toModel() }

        fun people(accountId: AccountId): Flow<List<Person>> =
            personDao.observeByAccount(accountId.value).map { rows -> rows.map { it.toModel() } }

        /** Everyone with this phone number, across accounts: the basis of merge suggestions. */
        suspend fun byPhoneNumber(e164: String): List<Person> = personDao.byPhoneNumber(e164).map { it.toModel() }

        fun byContact(contactId: ContactId): Flow<List<Person>> =
            personDao.observeByContact(contactId.value).map { rows -> rows.map { it.toModel() } }

        suspend fun upsert(person: Person) = personDao.upsert(person.toEntity())

        suspend fun delete(id: PersonId) = personDao.delete(id.value)

        /** Forgets an account's people whose handle ends with [suffix] and whom no chat lists. */
        suspend fun deleteStray(
            accountId: AccountId,
            suffix: String,
        ) = personDao.deleteStray(accountId.value, suffix)

        suspend fun deleteStrayHandle(
            accountId: AccountId,
            handle: String,
        ) = personDao.deleteStrayHandle(accountId.value, handle)

        fun mergeLinks(): Flow<List<MergeLink>> = mergeLinkDao.observeAll().map { rows -> rows.map { it.toModel() } }

        suspend fun linksForContact(contactId: ContactId): List<MergeLink> =
            mergeLinkDao.forContact(contactId.value).map { it.toModel() }

        suspend fun linksForPerson(personId: PersonId): List<MergeLink> =
            mergeLinkDao.forPerson(personId.value).map { it.toModel() }

        /** Records the user's confirmation of a merge (PingMe never merges on its own). */
        suspend fun confirmLink(link: MergeLink) = mergeLinkDao.upsert(link.toEntity())

        suspend fun removeLink(
            personId: PersonId,
            contactId: ContactId,
        ) = mergeLinkDao.delete(personId.value, contactId.value)
    }
