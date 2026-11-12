// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import org.pingme.core.model.AccountId
import org.pingme.core.model.AvatarSource
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
                photo = ownPhoto(chat, people),
                accountId = chat.accountId,
                detail =
                    other?.let {
                        it.phoneNumber
                            ?: it.networkHandle.takeIf { h -> !h.contains('@') && h != it.name }?.let { h -> "@$h" }
                    },
                hasContactPhoto = other?.contactPhoto != null,
            )
        }

        /** A phone contact's number as the Google Messages chat it would become (owner, 2026-10-04). */
        fun ofContact(
            offer: org.pingme.core.service.merge.ContactOffer,
            account: AccountId?,
        ) = PickableChat(
            id = offer.chatId,
            title = offer.name,
            network = NetworkId.GMESSAGES,
            photo = offer.photo,
            accountId = account,
            detail = offer.number,
            hasContactPhoto = offer.photo != null,
        )

        // The chat's own network picture first, the contact's photo only when it has none: a
        // suggestion card offers each member's picture as a choice, and the contact's photo is a
        // choice of its own (owner, 2026-11-07: Instagram pictures never showed).
        private fun ownPhoto(
            chat: Chat,
            people: Map<PersonId, Person>,
        ): String? {
            val own = chat.avatarSource as? AvatarSource.Picture ?: AvatarSource.Network(chat.accountId)
            return org.pingme.app.inbox
                .photoFor(chat.copy(avatarSource = own), people)
        }

        private const val YOU = "You"
    }
}
