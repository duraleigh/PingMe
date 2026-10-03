// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pingme.core.connector.AddressBook
import org.pingme.core.connector.AddressBookEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's address book, read only when the user has allowed it (READ_CONTACTS): names
 * and numbers, nothing else, and nothing leaves the phone except the numbers a connector
 * asks its own network about (Signal's directory lookup, owner Gate G7).
 */
@Singleton
class AndroidAddressBook
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AddressBook {
        fun allowed(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

        override suspend fun entries(): List<AddressBookEntry> =
            withContext(Dispatchers.IO) {
                if (!allowed()) return@withContext emptyList()
                val phonesByName = LinkedHashMap<String, MutableList<String>>()
                val projection =
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    )
                context.contentResolver
                    .query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)
                    ?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0).orEmpty().trim()
                            val number = cursor.getString(1).orEmpty().trim()
                            if (name.isEmpty() || number.isEmpty()) continue
                            phonesByName.getOrPut(name) { ArrayList() }.add(number)
                        }
                    }
                phonesByName.map { (name, phones) -> AddressBookEntry(name, phones.distinct()) }
            }
    }

@Module
@InstallIn(SingletonComponent::class)
interface AddressBookModule {
    @Binds
    fun addressBook(book: AndroidAddressBook): AddressBook
}
