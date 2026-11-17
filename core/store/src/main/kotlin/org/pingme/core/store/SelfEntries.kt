// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import org.pingme.core.store.db.PingMeDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Your own entries. Google Messages lists your number in many chats as an ordinary person,
 * named after the contact that holds your number; stored that way, it counted as the
 * other person and 73 chats showed your photo (owner, 2026-11-09). An entry with one of
 * your numbers (the number a "You" entry carries, on any network), or one inside the
 * one-to-one chats of many different people, is you.
 */
@Singleton
class SelfEntries
    @Inject
    constructor(
        db: PingMeDatabase,
    ) {
        private val dao = db.personDao()

        /** Makes every entry carrying your number yours, naming each first in [noteEach]; returns how many. */
        suspend fun repair(noteEach: (String) -> Unit = {}): Int {
            val wrong = dao.youUnderAnotherName(MANY_PEOPLE)
            wrong.forEach { noteEach("${it.id} '${it.displayName}' linked to '${it.contactName}'") }
            if (wrong.isNotEmpty()) dao.makeYou(wrong.map { it.id })
            return wrong.size
        }

        private companion object {
            // One person's own chats (a few networks, and a merged chat) never reach this.
            const val MANY_PEOPLE = 5
        }
    }
