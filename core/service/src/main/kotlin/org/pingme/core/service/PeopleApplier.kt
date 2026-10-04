// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Person
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
    ) {
        suspend fun apply(event: ConnectorEvent.PeopleUpdated) {
            event.people.forEach { contacts.upsert(it) }
            contacts.deleteStray(event.accountId, HIDDEN_ID_SUFFIX)
            PLACEHOLDER_HANDLES.forEach { handle ->
                contacts.deleteStrayHandle(event.accountId, handle)
                val placeholder = event.accountId.chat(handle)
                if (chats.get(placeholder) != null) {
                    chats.hide(placeholder, Instant.DISTANT_FUTURE)
                    chats.delete(placeholder)
                }
            }
            event.people.filter { Names.isReal(it.displayName) }.forEach { retitle(it) }
        }

        private suspend fun retitle(person: Person) {
            contacts
                .chatsWith(person.id)
                .filter { it.kind == ChatKind.DIRECT && Names.isBare(it.title) }
                .forEach { chat -> chats.update(chat.id) { it.copy(title = person.displayName) } }
        }
    }
