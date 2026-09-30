// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pingme.core.model.ChatId
import org.pingme.core.model.PersonId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Who is typing where, for the typing dots in the inbox and chat header. Never stored.
 * A typing signal fades after [EXPIRY] if the network never sends "stopped", so dots
 * never stick.
 */
@Singleton
class TypingTracker
    @Inject
    constructor(
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val state = MutableStateFlow<Map<ChatId, Set<PersonId>>>(emptyMap())
        private val expiries = mutableMapOf<Pair<ChatId, PersonId>, Job>()

        val typing: StateFlow<Map<ChatId, Set<PersonId>>> = state.asStateFlow()

        fun set(
            chatId: ChatId,
            personId: PersonId,
            typing: Boolean,
        ) {
            val key = chatId to personId
            synchronized(expiries) {
                expiries.remove(key)?.cancel()
                if (typing) {
                    expiries[key] =
                        scope.launch {
                            delay(EXPIRY)
                            set(chatId, personId, typing = false)
                        }
                }
            }
            state.update { current ->
                val people = current[chatId].orEmpty().let { if (typing) it + personId else it - personId }
                if (people.isEmpty()) current - chatId else current + (chatId to people)
            }
        }

        private companion object {
            val EXPIRY = 6.seconds
        }
    }
