// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.people

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.pingme.core.service.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps people in step with the phone's contacts (UI_DESIGN.md 10.18: "contact photos
 * refresh when the contact changes"): a change in the contacts provider, settled for a
 * moment, matches everyone again.
 */
@Singleton
class ContactsWatcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val linker: PeopleLinker,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private var observer: ContentObserver? = null
        private var pending: Job? = null

        /** Starts watching, and matches everyone now. */
        fun start() {
            if (observer == null) {
                val watch =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) = refreshSoon()
                    }
                try {
                    context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, watch)
                    observer = watch
                } catch (e: SecurityException) {
                    Log.i(TAG, "Contacts are not allowed yet; not watching them", e)
                }
            }
            refreshSoon()
        }

        fun stop() {
            observer?.let { context.contentResolver.unregisterContentObserver(it) }
            observer = null
            pending?.cancel()
        }

        /** Matches everyone again once changes have settled. */
        fun refreshSoon() {
            pending?.cancel()
            pending =
                scope.launch {
                    delay(SETTLE)
                    runCatching { linker.relinkAll() }
                        .onFailure { Log.w(TAG, "Could not match people to contacts", it) }
                }
        }

        private companion object {
            const val TAG = "PingMeContacts"
            val SETTLE = 2.seconds
        }
    }
