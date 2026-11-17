// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

/**
 * The phone's address book, as a connector may read it: names and phone numbers. The app
 * supplies the real one (ContactsContract, when the user has allowed it); tests supply a
 * list. Signal uses it to find which contacts are on Signal (owner, Gate G7).
 */
interface AddressBook {
    suspend fun entries(): List<AddressBookEntry>

    /** No address book: nothing to look up. */
    object None : AddressBook {
        override suspend fun entries(): List<AddressBookEntry> = emptyList()
    }
}

/**
 * One contact: [phones] in international form (+ and digits), [id] the phone's lookup key
 * for the contact, [photo] a content address for its photo, when it has one.
 */
data class AddressBookEntry(
    val name: String,
    val phones: List<String>,
    val id: String? = null,
    val photo: String? = null,
)
