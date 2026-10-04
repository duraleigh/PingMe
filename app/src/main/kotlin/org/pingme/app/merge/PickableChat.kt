// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import org.pingme.core.model.AccountId
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId

/**
 * One chat the picker and the suggestion cards offer: its name, network, photo, and the
 * phone number or username it goes by, so two chats with one person on one network can
 * be told apart (owner, 2026-10-03).
 */
data class PickableChat(
    val id: ChatId,
    val title: String,
    val network: NetworkId,
    val photo: String? = null,
    val accountId: AccountId? = null,
    /** The other person's number, or their username on a network without numbers. */
    val detail: String? = null,
    /** True when the other person has a contact photo to offer for a merged chat. */
    val hasContactPhoto: Boolean = false,
) {
    companion object {
        fun of(
            chat: Chat,
            network: NetworkId,
            people: Map<PersonId, Person>,
        ): PickableChat {
            val other = chat.participants.mapNotNull { people[it] }.firstOrNull { it.displayName != YOU }
            return PickableChat(
                id = chat.id,
                title = chat.nameOverride ?: chat.title,
                network = network,
                photo =
                    org.pingme.app.inbox
                        .photoFor(chat, people),
                accountId = chat.accountId,
                detail =
                    other?.let {
                        it.phoneNumber
                            ?: it.networkHandle.takeIf { h -> !h.contains('@') && h != it.name }?.let { h -> "@$h" }
                    },
                hasContactPhoto = other?.contactPhoto != null,
            )
        }

        private const val YOU = "You"
    }
}
