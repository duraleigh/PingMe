// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.model.ChatId
import org.pingme.core.service.work.Work
import org.pingme.core.store.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first sync of history (DESIGN.md 5.4 step 4): once an account's chat list arrives, each
 * chat with nothing stored yet fetches its messages in the background, so the inbox shows real
 * last messages and a chat opens with its history.
 */
@Singleton
open class HistorySync
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val messages: MessageRepository,
    ) {
        suspend fun chatsArrived(chats: List<ChatSnapshot>) {
            chats.filter { messages.oldest(it.id) == null }.forEach { backfill(it.id) }
        }

        protected open fun backfill(chatId: ChatId) {
            Work.backfill(context, chatId)
        }
    }
