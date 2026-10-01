// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.model.ChatId
import org.pingme.core.model.Message
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.PersonId
import org.pingme.core.model.Transport
import kotlin.time.Clock

/** A new bubble plays its entrance once (UI_DESIGN.md 4.5). */
class EntranceTest {
    private fun msg(
        id: String,
        body: String,
        outgoing: Boolean,
    ): Message {
        val at = Clock.System.now()
        return Message(
            MessageId(id),
            ChatId("a/c"),
            PersonId(if (outgoing) "me" else "sam"),
            at,
            at,
            body,
            MessageKind.TEXT,
            emptyList(),
            null,
            null,
            null,
            false,
            if (outgoing) MessageStatus.Sending else MessageStatus.Delivered,
            emptyList(),
            Transport.NETWORK,
            id,
            null,
            isOutgoing = outgoing,
        )
    }

    @Test
    fun anArrivingMessageEntersOnceEvenWhenScrolledBackTo() {
        val ui = ChatUi()
        val hi = msg("a/m1", "hi", outgoing = false)
        assertTrue(ui.enters(hi))
        assertFalse(ui.enters(hi))
        assertTrue("another message with the same words is its own", ui.enters(msg("a/m2", "hi", outgoing = false)))
    }

    @Test
    fun yourMessageEntersOnceThoughTheNetworksCopyReplacesTheSendingOne() {
        val ui = ChatUi()
        assertTrue(ui.enters(msg("a/pending-1", "on my way", outgoing = true)))
        assertFalse(ui.enters(msg("a/net-77", "on my way", outgoing = true)))
        assertTrue(ui.enters(msg("a/pending-2", "something else", outgoing = true)))
    }
}
