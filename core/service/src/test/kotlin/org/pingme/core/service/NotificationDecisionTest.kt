// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.connector.ChatSnapshot
import kotlin.time.Duration.Companion.minutes

class NotificationDecisionTest : ServiceTest() {
    private val notify = NotificationDecision.Notify(NotificationRouter.DEFAULT_CHANNEL)

    private fun chat() =
        chatSnapshot().let { s: ChatSnapshot ->
            org.pingme.core.model.Chat(
                s.id,
                s.accountId,
                s.kind,
                s.title,
                emptyList(),
                0,
                now,
                false,
                null,
                false,
                null,
                false,
                false,
                false,
                null,
                null,
                null,
                org.pingme.core.model.AvatarSource.Contacts,
                null,
                null,
                "c1",
            )
        }

    @Test
    fun incomingMessagesNotifyUnlessTheChatIsSilent() {
        val incoming = messageSnapshot("m").message
        assertEquals(notify, NotificationRouter.decide(chat(), incoming, now))
        assertEquals(
            NotificationDecision.Drop,
            NotificationRouter.decide(chat(), messageSnapshot("o", outgoing = true).message, now),
        )
        assertEquals(NotificationDecision.Drop, NotificationRouter.decide(chat().copy(isMuted = true), incoming, now))
        assertEquals(
            NotificationDecision.Drop,
            NotificationRouter.decide(chat().copy(isLowPriority = true), incoming, now),
        )
        val mutedUntilLater = chat().copy(isMuted = true, muteUntil = now + 1.minutes)
        assertEquals(NotificationDecision.Drop, NotificationRouter.decide(mutedUntilLater, incoming, now))
        val muteOver = chat().copy(isMuted = true, muteUntil = now - 1.minutes)
        assertEquals(notify, NotificationRouter.decide(muteOver, incoming, now))
    }
}
