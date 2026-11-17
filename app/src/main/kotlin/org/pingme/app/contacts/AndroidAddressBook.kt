// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's address book, read only when the user has allowed it (READ_CONTACTS): each
 * contact's name, numbers in international form, lookup key, and photo address. Nothing
 * leaves the phone except the numbers a connector asks its own network about (Signal's
 * directory lookup, owner Gate G7). The matching itself is the service's PeopleLinker
 * (UI_DESIGN.md 10.18, BUILD_PLAN.md P7.1).
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
                val country = countryIso()
                val byContact = LinkedHashMap<String, Draft>()
                context.contentResolver
                    .query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, PROJECTION, null, null, null)
                    ?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(LOOKUP).orEmpty()
                            val name = cursor.getString(NAME).orEmpty().trim()
                            val number = cursor.getString(NUMBER).orEmpty().trim()
                            if (key.isEmpty() || name.isEmpty() || number.isEmpty()) continue
                            val phone =
                                cursor.getString(NORMALIZED)?.takeIf { it.isNotBlank() } ?: e164(number, country)
                            val draft = byContact.getOrPut(key) { Draft(name, cursor.getString(PHOTO)) }
                            if (phone != null && phone !in draft.phones) draft.phones += phone
                        }
                    }
                byContact.map { (key, draft) -> AddressBookEntry(draft.name, draft.phones.toList(), key, draft.photo) }
            }

        // The phone's country decides what a number written without one means.
        private fun countryIso(): String {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            return telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
                ?: telephony?.simCountryIso?.takeIf { it.isNotBlank() }
                ?: Locale.getDefault().country
        }

        private fun e164(
            number: String,
            country: String,
        ): String? =
            PhoneNumberUtils.formatNumberToE164(number, country.uppercase(Locale.ROOT))
                ?: number.filter { it.isDigit() }.takeIf { it.length >= MIN_DIGITS }?.let { "+$it" }

        private class Draft(
            val name: String,
            val photo: String?,
        ) {
            val phones = LinkedHashSet<String>()
        }

        private companion object {
            val PROJECTION =
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                )
            const val LOOKUP = 0
            const val NAME = 1
            const val NUMBER = 2
            const val NORMALIZED = 3
            const val PHOTO = 4
            const val MIN_DIGITS = 7
        }
    }

@Module
@InstallIn(SingletonComponent::class)
interface AddressBookModule {
    @Binds
    fun addressBook(book: AndroidAddressBook): AddressBook
}
