// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.connectors.signal.bridge.SigChat
import org.pingme.connectors.signal.bridge.SigEdit
import org.pingme.connectors.signal.bridge.SigEvent
import org.pingme.connectors.signal.bridge.SigMedia
import org.pingme.connectors.signal.bridge.SigMember
import org.pingme.connectors.signal.bridge.SigMessage
import org.pingme.connectors.signal.bridge.SigQuote
import org.pingme.connectors.signal.bridge.SigReaction
import org.pingme.connectors.signal.bridge.SigReceipt
import org.pingme.connectors.signal.bridge.SigTranslate
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.model.AccountId
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatKind
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus

class SigTranslateTest {
    private val account = AccountId("sig-1")
    private val go = SigTranslate(account).apply { ownId = FakeSignal.ME }

    @Test
    fun aChatNamesThePersonAndKeepsUnread() {
        val chat =
            go.chat(
                SigChat(
                    FakeSignal.SAM,
                    members = listOf(SigMember(FakeSignal.SAM, "+15555550123", "Sam")),
                    unread = 2,
                    lastAt = 5,
                ),
            )
        assertEquals("Sam", chat.title)
        assertEquals(ChatKind.DIRECT, chat.kind)
        assertEquals(2, chat.unreadCount)
        assertEquals(listOf("You", "Sam"), chat.participants.map { it.displayName })
        val group =
            go.chat(
                SigChat(
                    "g".repeat(44),
                    isGroup = true,
                    name = "Lunch",
                    members = listOf(SigMember(FakeSignal.SAM, name = "Sam"), SigMember(FakeSignal.ME, isMe = true)),
                ),
            )
        assertEquals(ChatKind.GROUP, group.kind)
        assertEquals("Lunch", group.title)
    }

    @Test
    fun aMessageWithAQuoteAndAVoiceNoteFlattens() {
        val quoted = SigQuote("${FakeSignal.ME}:1", FakeSignal.ME, "What time?")
        val msg =
            SigMessage(
                "${FakeSignal.SAM}:2",
                FakeSignal.SAM,
                FakeSignal.SAM,
                "+1555",
                false,
                2,
                "voice",
                "",
                SigMedia("{}", "audio/aac", voice = true, size = 9),
                quoted,
            )
        val snapshot = go.message(msg)
        assertEquals(MessageKind.VOICE, snapshot.message.kind)
        assertEquals(
            AttachmentKind.VOICE,
            snapshot.message.attachments
                .single()
                .kind,
        )
        assertEquals("You", snapshot.message.quote?.senderName)
        assertEquals("What time?", snapshot.message.quote?.text)
        assertEquals(MessageStatus.Delivered, snapshot.message.status)
        assertEquals("+1555", snapshot.sender?.phoneNumber)
    }

    @Test
    fun receiptsFindYourMessagesByTimestamp() {
        go.chat(SigChat(FakeSignal.SAM))
        go.message(
            SigMessage("${FakeSignal.ME}:7", FakeSignal.SAM, FakeSignal.ME, "", true, 7, "text", "hi", status = "sent"),
        )
        val events = go.translate(SigEvent.Receipt(SigReceipt(FakeSignal.SAM, "read", listOf(7))))
        val status = events.single() as ConnectorEvent.StatusChanged
        assertEquals(MessageStatus.Read, status.status)
        assertEquals("sig-1/${FakeSignal.SAM}/${FakeSignal.ME}:7", status.messageId.value)
        // A tick never goes backwards.
        assertTrue(
            go.translate(SigEvent.Receipt(SigReceipt(FakeSignal.SAM, "delivered", listOf(7)))).all {
                (it as ConnectorEvent.StatusChanged).status == MessageStatus.Read
            },
        )
    }

    @Test
    fun reactionsEditsAndDeletesNameTheirTargets() {
        val reaction =
            SigMessage(
                "${FakeSignal.SAM}:9",
                FakeSignal.SAM,
                FakeSignal.SAM,
                "",
                false,
                9,
                "reaction",
                reaction = SigReaction(FakeSignal.ME, 7, emoji = "❤️"),
            )
        val r = go.translate(SigEvent.Message(reaction)).single() as ConnectorEvent.ReactionChanged
        assertEquals("sig-1/${FakeSignal.SAM}/${FakeSignal.ME}:7", r.messageId.value)
        assertEquals("❤️", r.reaction.emoji)
        val edit =
            SigMessage(
                "${FakeSignal.SAM}:10",
                FakeSignal.SAM,
                FakeSignal.SAM,
                "",
                false,
                10,
                "edit",
                edit = SigEdit(3, "fixed"),
            )
        val e = go.translate(SigEvent.Message(edit)).single() as ConnectorEvent.MessageEdited
        assertEquals("sig-1/${FakeSignal.SAM}/${FakeSignal.SAM}:3", e.messageId.value)
        assertEquals("fixed", e.body)
    }

    @Test
    fun aMessageInAnUnknownChatMakesTheChatFirst() {
        val msg = SigMessage("${FakeSignal.SAM}:2", FakeSignal.SAM, FakeSignal.SAM, "+1555", false, 2, "text", "hey")
        val events = go.translate(SigEvent.Message(msg))
        assertTrue(events[0] is ConnectorEvent.ChatUpdated)
        assertTrue(events[1] is ConnectorEvent.NewMessage)
        assertEquals("+1555", (events[0] as ConnectorEvent.ChatUpdated).chat.title)
    }
}
