// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.pingme.core.model.MessageId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stops a page of messages from the network undoing a reaction change made while the page was
 * on its way. Opening a chat fetches its newest page (owner, 2026-10-07); on a slow network a
 * page asked for before a double tap could be saved after it, and saving a page replaces each
 * message's reactions with the network's copy, so the new heart vanished until the network's
 * next copy (BUILD_LOG 2026-11-05, "A test that failed once on GitHub").
 *
 * Every change to a message's reactions (yours, or one the network reports as it happens) is
 * numbered in order. A page remembers the number reached when it was asked for; on saving it,
 * a message whose reactions changed since keeps its stored reactions, which are newer than the
 * page's. Every other part of the message is saved from the page as before, and a page asked
 * for after the last change replaces the reactions as before, so a reaction taken away on the
 * network is still taken away here.
 */
@Singleton
class ReactionEdits
    @Inject
    constructor() {
        private val lock = Mutex()
        private var count = 0L

        // The newest change number per message; only the latest few hundred are kept, far more
        // than can change while one page is on its way.
        private val changed =
            object : LinkedHashMap<MessageId, Long>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MessageId, Long>?) = size > KEPT
            }

        /** Taken just before a page is asked for; passed back to [save]. */
        suspend fun mark(): Long = lock.withLock { count }

        /** Changes the reactions on [id] through [change], numbered after every mark so far. */
        suspend fun <T> change(
            id: MessageId,
            change: suspend () -> T,
        ): T =
            lock.withLock {
                count++
                changed.remove(id)
                changed[id] = count
                change()
            }

        /**
         * Saves a page's copy of [id] through [save], told whether the stored reactions changed
         * after [mark] and so are newer than the page's. Held against [change], so a reaction
         * cannot land between the look and the save.
         */
        suspend fun <T> save(
            id: MessageId,
            mark: Long,
            save: suspend (storedIsNewer: Boolean) -> T,
        ): T = lock.withLock { save((changed[id] ?: 0L) > mark) }

        private companion object {
            const val KEPT = 500
        }
    }
