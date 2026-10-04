// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Person
import org.pingme.core.service.people.PeopleLinker
import org.pingme.core.service.people.ProfilePhotos
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Writes an account's people list into the store. Hidden-id rows an earlier build stored
 * go once nothing lists them, and so does the chat for a network's "nobody" placeholder
 * (owner, Gate G7). A one-to-one chat still titled by a bare number or raw id takes the
 * person's name: WhatsApp never lists one-to-one chats again after pairing, so a chat
 * stored before the names arrived kept its number for good (owner, Gate G7, round 2).
 */
@Singleton
class PeopleApplier
    @Inject
    constructor(
        private val chats: ChatRepository,
        private val contacts: ContactRepository,
        private val linker: PeopleLinker,
        private val photos: ProfilePhotos,
    ) {
        /**
         * Stores a person as a network reports them, with the phone's contact link applied
         * and the network's profile photo on its way into app storage.
         */
        suspend fun remember(person: Person) {
            linker.ensureLoaded()
            contacts.upsert(linker.linked(withLocalPhoto(person)))
        }

        private fun withLocalPhoto(person: Person): Person {
            val url = person.avatarPath?.takeIf { photos.isRemote(it) } ?: return person
            val local = photos.cached(url)
            if (local == null) photos.fetchSoon(person.id, url)
            return person.copy(avatarPath = local ?: url)
        }

        /** The title a one-to-one chat should carry: the contact's name over a bare number. */
        suspend fun titleFor(snapshot: ChatSnapshot): String {
            if (snapshot.kind != ChatKind.DIRECT || !Names.isBare(snapshot.title)) return snapshot.title
            linker.ensureLoaded()
            return snapshot.participants
                .map { linker.linked(it) }
                .firstOrNull { it.contactName != null && it.displayName != "You" }
                ?.contactName ?: snapshot.title
        }

        suspend fun apply(event: ConnectorEvent.PeopleUpdated) {
            event.people.forEach { remember(it) }
            contacts.deleteStray(event.accountId, HIDDEN_ID_SUFFIX)
            PLACEHOLDER_HANDLES.forEach { handle ->
                contacts.deleteStrayHandle(event.accountId, handle)
                val placeholder = event.accountId.chat(handle)
                if (chats.get(placeholder) != null) {
                    chats.hide(placeholder, Instant.DISTANT_FUTURE)
                    chats.delete(placeholder)
                }
            }
            event.people.forEach { linker.retitle(linker.linked(it)) }
        }
    }
