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

data class AddressBookEntry(
    val name: String,
    val phones: List<String>,
)
