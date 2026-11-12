// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.people

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.pingme.core.connector.AddressBook
import org.pingme.core.connector.AddressBookEntry
import org.pingme.core.connector.remoteId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ContactId
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.service.Names
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matches people to the phone's contacts by phone number (UI_DESIGN.md 10.18, BUILD_PLAN.md
 * P7.1). The address book is read into an index keyed by digits; a person whose number is
 * in it carries the contact's lookup key, name, and photo, and a one-to-one chat still
 * titled by a bare number takes the contact's name. With no address book (contacts not
 * allowed, or none), existing links are left alone.
 */
@Singleton
class PeopleLinker
    @Inject
    constructor(
        private val book: AddressBook,
        private val contacts: ContactRepository,
        private val chats: ChatRepository,
    ) {
        private val lock = Mutex()

        private companion object {
            const val YOU = "You"
        }

        @Volatile private var index: Map<String, AddressBookEntry> = emptyMap()

        @Volatile private var loaded = false

        /** Reads the address book once; [relinkAll] reads it again. */
        suspend fun ensureLoaded() {
            if (!loaded) lock.withLock { if (!loaded) load() }
        }

        /** [person] with the phone's link applied, from the index as it stands. */
        fun linked(person: Person): Person {
            // Your own entry is never a contact of yours: linking it named every chat you are in
            // after the card that holds your number (owner, Gate G7, round 3).
            if (!loaded || isSelf(person)) return person
            val entry = match(person.phoneNumber) ?: return person
            val id = entry.id ?: return person
            return person.copy(contactId = ContactId(id), contactName = entry.name, contactPhoto = entry.photo)
        }

        /** Reads the address book again and brings every stored person into line with it. */
        suspend fun relinkAll() =
            lock.withLock {
                load()
                if (!loaded) return@withLock
                contacts.withPhones().forEach { person ->
                    if (isSelf(person)) return@forEach unlinkSelf(person)
                    val entry = match(person.phoneNumber)
                    val id = entry?.id?.let(::ContactId)
                    val same =
                        id == person.contactId && entry?.name == person.contactName &&
                            entry?.photo == person.contactPhoto
                    if (same) return@forEach
                    contacts.link(person.id, id, entry?.name, entry?.photo)
                    retitle(person.copy(contactId = id, contactName = entry?.name, contactPhoto = entry?.photo))
                }
            }

        /** A one-to-one chat with [person] still titled by a bare number or raw id takes their name. */
        suspend fun retitle(person: Person) {
            val name = person.name
            if (!Names.isReal(name) || isSelf(person)) return
            contacts
                .chatsWith(person)
                .filter { it.kind == ChatKind.DIRECT && Names.isBare(it.title) }
                .forEach { chat ->
                    chats.update(chat.id) {
                        it.copy(title = name, participants = (it.participants + person.id).distinct())
                    }
                }
        }

        /**
         * An earlier build linked your own entry to a contact and named every chat after it.
         * The link goes, and each of those chats takes its own person's name or number back.
         */
        private suspend fun unlinkSelf(self: Person) {
            if (self.contactId != null) contacts.link(self.id, null, null, null)
            // The card that holds your own number names it, linked or not: every chat still
            // titled after it that is not with that contact takes its own name or address.
            val wrong = self.contactName ?: match(self.phoneNumber)?.name ?: return
            contacts.chatsTitled(self.accountId, wrong).filter { it.kind == ChatKind.DIRECT }.forEach { chat ->
                val own = contacts.person(PersonId(chat.id.value))
                val right =
                    own?.name?.takeIf { Names.isReal(it) } ?: own?.phoneNumber ?: own?.displayName
                        ?: Names.address(chat.networkRemoteId)
                if (right != wrong) {
                    chats.update(chat.id) {
                        it.copy(title = right, participants = (it.participants + listOfNotNull(own?.id)).distinct())
                    }
                }
            }
        }

        private fun isSelf(person: Person) = person.displayName == YOU || person.id.remoteId == "me"

        private suspend fun load() {
            val entries = book.entries()
            index = buildIndex(entries)
            loaded = entries.isNotEmpty()
        }

        private fun match(phone: String?): AddressBookEntry? {
            val digits = Numbers.digits(phone) ?: return null
            return index[digits] ?: Numbers.national(digits)?.let { index[it] }
        }

        private fun buildIndex(entries: List<AddressBookEntry>): Map<String, AddressBookEntry> {
            val index = HashMap<String, AddressBookEntry>()
            entries.forEach { entry ->
                entry.phones.forEach { phone ->
                    val digits = Numbers.digits(phone) ?: return@forEach
                    // The first contact with a number keeps it; a shared number is rare and ambiguous.
                    index.putIfAbsent(digits, entry)
                    Numbers.national(digits)?.let { index.putIfAbsent(it, entry) }
                }
            }
            return index
        }
    }
