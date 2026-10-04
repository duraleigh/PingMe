// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pingme.core.model.AccountId
import org.pingme.core.model.Chat
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
        private val chatDao = db.chatDao()

        suspend fun person(id: PersonId): Person? = personDao.get(id.value)?.toModel()

        fun observePerson(id: PersonId): Flow<Person?> = personDao.observe(id.value).map { it?.toModel() }

        fun people(accountId: AccountId): Flow<List<Person>> =
            personDao.observeByAccount(accountId.value).map { rows -> rows.map { it.toModel() } }

        /** Everyone with this phone number, across accounts: the basis of merge suggestions. */
        suspend fun byPhoneNumber(e164: String): List<Person> = personDao.byPhoneNumber(e164).map { it.toModel() }

        fun byContact(contactId: ContactId): Flow<List<Person>> =
            personDao.observeByContact(contactId.value).map { rows -> rows.map { it.toModel() } }

        /**
         * Writes a person as a network reports them. The phone's contact link is local, so
         * a snapshot that carries none keeps the one already stored.
         */
        suspend fun upsert(person: Person) {
            val kept =
                if (person.contactId == null && person.contactName == null && person.contactPhoto == null) {
                    personDao.get(person.id.value)?.let { old ->
                        person.copy(
                            contactId = old.contactId?.let(::ContactId),
                            contactName = old.contactName,
                            contactPhoto = old.contactPhoto,
                        )
                    } ?: person
                } else {
                    person
                }
            personDao.upsert(kept.toEntity())
        }

        /** Everyone with a phone number, for matching against the address book. */
        suspend fun withPhones(): List<Person> = personDao.withPhones().map { it.toModel() }

        /** Everyone some chat lists, keyed by id. */
        fun inChats(): Flow<Map<PersonId, Person>> =
            personDao.observeInChats().map { rows -> rows.associate { PersonId(it.id) to it.toModel() } }

        /** Records where a network profile photo was saved on this phone. */
        suspend fun setAvatar(
            id: PersonId,
            path: String,
        ) = personDao.setAvatar(id.value, path)

        /** Sets or clears the phone's contact link for one person (UI_DESIGN.md 10.18). */
        suspend fun link(
            id: PersonId,
            contactId: ContactId?,
            contactName: String?,
            contactPhoto: String?,
        ) = personDao.link(id.value, contactId?.value, contactName, contactPhoto)

        /**
         * The one-to-one chats that are [person]'s: the ones listing them, the chat whose id is
         * theirs (WhatsApp, Signal, and Telegram address a person's chat by the person), and
         * the ones still titled by their number. A chat made from your own outgoing message
         * lists nobody, so the id and the number are what find it (owner, Gate G7, round 3).
         */
        suspend fun chatsWith(person: Person): List<Chat> {
            val listed = chatDao.withParticipant(person.id.value)
            val own = chatDao.get(person.id.value)
            val titles = person.phoneNumber?.let { listOf(it, it.removePrefix("+")) }.orEmpty()
            val titled = if (titles.isEmpty()) emptyList() else chatDao.titled(person.accountId.value, titles)
            return (listed + listOfNotNull(own) + titled).map { it.toModel() }.distinctBy { it.id }
        }

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
