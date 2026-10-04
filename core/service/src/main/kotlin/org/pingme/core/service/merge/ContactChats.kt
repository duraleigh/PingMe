// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.merge

import org.pingme.core.connector.AddressBook
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.ContactId
import org.pingme.core.model.NetworkId
import org.pingme.core.service.ChatActions
import org.pingme.core.service.people.Numbers
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ContactRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A phone contact's number offered as a Google Messages chat to merge with, before any
 * text has ever been sent to it (owner, 2026-10-04: "if I have the phone number in my
 * contacts then it should be available as a Google Messages chat to merge"). Its chat id
 * is a stand-in until the merge, when the real chat is started.
 */
data class ContactOffer(
    val name: String,
    val number: String,
    val digits: String,
    val contactId: ContactId?,
    val photo: String?,
) {
    val chatId: ChatId get() = ChatId("$PREFIX$digits")

    companion object {
        const val PREFIX = "contact/"

        fun isOffer(id: ChatId) = id.value.startsWith(PREFIX)

        fun digitsOf(id: ChatId) = id.value.removePrefix(PREFIX)
    }
}

/** The offers on hand and the account they would start chats on. */
data class Offers(
    val list: List<ContactOffer> = emptyList(),
    val account: AccountId? = null,
) {
    val byId: Map<ChatId, ContactOffer> get() = list.associateBy { it.chatId }
}

/** Reads the address book for offers and starts the real chat when an offer is merged. */
@Singleton
class ContactChats
    @Inject
    constructor(
        private val addressBook: AddressBook,
        private val accounts: AccountRepository,
        private val contacts: ContactRepository,
        private val actions: ChatActions,
    ) {
        private var cached: Pair<Long, Offers>? = null

        /** Every contact number without a Google Messages chat yet; none without such an account. */
        suspend fun offers(): Offers {
            cached?.let { (at, offers) -> if (System.currentTimeMillis() - at < CACHE_MS) return offers }
            val account = accounts.getAll().firstOrNull { it.network == NetworkId.GMESSAGES }?.id
            val offers =
                if (account == null) {
                    Offers()
                } else {
                    val known =
                        contacts
                            .withPhones()
                            .filter { it.accountId == account }
                            .mapNotNull { Numbers.digits(it.phoneNumber) }
                            .toSet()
                    val seen = HashSet<String>()
                    val list =
                        addressBook.entries().flatMap { entry ->
                            entry.phones.mapNotNull { phone ->
                                val digits = Numbers.digits(phone) ?: return@mapNotNull null
                                if (digits in known || !seen.add(digits)) return@mapNotNull null
                                ContactOffer(entry.name, phone, digits, entry.id?.let(::ContactId), entry.photo)
                            }
                        }
                    Offers(list, account)
                }
            cached = System.currentTimeMillis() to offers
            return offers
        }

        /** The real chats for [ids]: an offer becomes a started Google Messages chat, the rest pass through. */
        suspend fun resolve(ids: List<ChatId>): List<ChatId> {
            if (ids.none(ContactOffer::isOffer)) return ids
            val account =
                accounts.getAll().firstOrNull { it.network == NetworkId.GMESSAGES }?.id
                    ?: throw MergeRefusedException("No Google Messages account to text that number from.")
            return ids.map { id ->
                if (ContactOffer.isOffer(id)) {
                    cached = null
                    actions.startChat(account, "+" + ContactOffer.digitsOf(id))
                } else {
                    id
                }
            }
        }

        private companion object {
            const val CACHE_MS = 60_000L
        }
    }
