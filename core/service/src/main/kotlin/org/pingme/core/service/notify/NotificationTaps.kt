// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.notify

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pingme.core.model.ChatId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a tapped notification to the screen (owner, Gate G2: tapping did nothing). The
 * notification's intent names the chat; the activity hands it here; the navigation host
 * opens that chat and takes the request down.
 */
@Singleton
class NotificationTaps
    @Inject
    constructor() {
        private val wanted = MutableStateFlow<ChatId?>(null)

        /** The chat a tap asked for, until the screen has opened it. */
        val requested: StateFlow<ChatId?> = wanted.asStateFlow()

        /** Reads the chat out of an activity's intent, if a notification sent it. */
        fun fromIntent(intent: Intent?) {
            chatIn(intent)?.let { wanted.value = it }
        }

        fun consume() {
            wanted.value = null
        }

        companion object {
            const val EXTRA_CHAT = "org.pingme.chat"

            fun chatIn(intent: Intent?): ChatId? = intent?.getStringExtra(EXTRA_CHAT)?.let(::ChatId)
        }
    }
