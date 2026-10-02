// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.connectors.whatsapp.bridge.WaChat
import org.pingme.connectors.whatsapp.bridge.WaEvent
import org.pingme.connectors.whatsapp.bridge.WaMedia
import org.pingme.connectors.whatsapp.bridge.WaMessage
import org.pingme.connectors.whatsapp.bridge.WaParticipant
import org.pingme.connectors.whatsapp.bridge.WaReaction
import org.pingme.connectors.whatsapp.bridge.WaReceipt
import org.pingme.connectors.whatsapp.bridge.WaTarget
import org.pingme.connectors.whatsapp.bridge.WaTranslate
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.space
import org.pingme.core.model.AccountId
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatKind
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus

/** What the bridge reports becomes PingMe's model (BUILD_PLAN.md Phase 6, network 1). */
class WaTranslateTest {
    private val account = AccountId("acct")
    private val go = WaTranslate(account, contactName = { if (it == SAM) "Sam Ortiz" else "" }).apply { ownId = ME }

    private fun text(
        id: String,
        chat: String = SAM,
        fromMe: Boolean = false,
        at: Long = 1_759_310_000_000,
        body: String = "hi",
    ) = WaMessage(
        id,
        chat,
        if (fromMe) ME else SAM,
        "15555550123",
        "Sam",
        fromMe,
        at,
        "text",
        body,
        status = if (fromMe) "sent" else "",
    )

    @Test
    fun aMessageGetsChatScopedIdsAndItsSendersName() {
        val events = go.translate(WaEvent.Message(text("M1")))
        val chat = events.filterIsInstance<ConnectorEvent.ChatUpdated>().single().chat
        assertEquals(account.chat(SAM), chat.id)
        assertEquals(ChatKind.DIRECT, chat.kind)
        assertEquals("Sam Ortiz", chat.title)
        assertEquals(listOf("You", "Sam Ortiz"), chat.participants.map { it.displayName })
        val message = events.filterIsInstance<ConnectorEvent.NewMessage>().single().message
        assertEquals(account.message("$SAM/M1"), message.message.id)
        assertEquals("Sam Ortiz", message.sender?.displayName)
        assertEquals(MessageStatus.Delivered, message.message.status)
        // Seen again: an update, not a new message.
        assertTrue(go.translate(WaEvent.Message(text("M1"))).single() is ConnectorEvent.MessageUpdated)
    }

    @Test
    fun mediaReactionsRevokesAndEditsTranslate() {
        val voice =
            text(
                "V1",
            ).copy(
                kind = "voice",
                text = "",
                media =
                    WaMedia(
                        type = "WhatsApp Audio Keys",
                        mime = "audio/ogg; codecs=opus",
                        seconds = 4,
                        voice = true,
                    ),
            )
        val message = go.message(voice).message
        assertEquals(MessageKind.VOICE, message.kind)
        assertEquals(AttachmentKind.VOICE, message.attachments.single().kind)
        assertEquals("audio/ogg", message.attachments.single().mimeType)
        assertEquals(4000L, message.attachments.single().durationMs)
        assertNull(message.body)

        val reaction =
            go
                .translate(
                    WaEvent.Message(
                        text("R1").copy(kind = "reaction", text = "", reaction = WaReaction("V1", emoji = "❤️")),
                    ),
                ).single()
        assertEquals(account.message("$SAM/V1"), (reaction as ConnectorEvent.ReactionChanged).messageId)
        assertEquals("❤️", reaction.reaction.emoji)
        val revoke =
            go
                .translate(
                    WaEvent.Message(text("R2").copy(kind = "revoke", text = "", revoke = WaTarget("V1"))),
                ).single()
        assertEquals(account.message("$SAM/V1"), (revoke as ConnectorEvent.MessageRevoked).messageId)
        val edit =
            go
                .translate(
                    WaEvent.Message(
                        text(
                            "R3",
                        ).copy(
                            kind = "edit",
                            text = "",
                            edit =
                                org.pingme.connectors.whatsapp.bridge
                                    .WaEdit("M9", "fixed"),
                        ),
                    ),
                ).single()
        assertEquals("fixed", (edit as ConnectorEvent.MessageEdited).body)
    }

    @Test
    fun receiptsTickOnlyYourOwnMessagesAndNeverBackwards() {
        go.translate(WaEvent.Message(text("O1", fromMe = true)))
        go.translate(WaEvent.Message(text("I1")))
        val read = go.translate(WaEvent.Receipt(WaReceipt(SAM, SAM, listOf("O1", "I1"), "read")))
        val ticks = read.filterIsInstance<ConnectorEvent.StatusChanged>()
        assertEquals(listOf(account.message("$SAM/O1")), ticks.map { it.messageId })
        assertEquals(MessageStatus.Read, ticks.single().status)
        val later = go.translate(WaEvent.Receipt(WaReceipt(SAM, SAM, listOf("O1"), "delivered")))
        assertEquals(MessageStatus.Read, (later.single() as ConnectorEvent.StatusChanged).status)
    }

    @Test
    fun historyBringsTheChatItsMessagesThenWhatRefersToThem() {
        val chat = WaChat(SAM, unread = 2, lastMessageAt = 5, participants = listOf(WaParticipant(SAM, "15555550123")))
        val reaction = text("R1", at = 3).copy(kind = "reaction", text = "", reaction = WaReaction("H1", emoji = "👍"))
        val events =
            go.translate(
                WaEvent.History("RECENT", 100, chat, listOf(reaction, text("H2", at = 2), text("H1", at = 1))),
            )
        assertTrue(events[0] is ConnectorEvent.ChatUpdated)
        assertEquals(2, (events[0] as ConnectorEvent.ChatUpdated).chat.unreadCount)
        val batch = events[1] as ConnectorEvent.HistoryBatch
        assertEquals(listOf("H2", "H1"), batch.messages.map { it.message.networkRemoteId })
        assertTrue(events[2] is ConnectorEvent.ReactionChanged)
        // The page is remembered, newest first, for the history worker.
        assertEquals(listOf("H2", "H1"), go.recentPage(SAM, null, 5)!!.map { it.message.networkRemoteId })
        assertEquals(listOf("H1"), go.recentPage(SAM, "H2", 5)!!.map { it.message.networkRemoteId })
        assertNull(go.recentPage(SAM, "unknown", 5))
    }

    @Test
    fun groupsInACommunityBecomeChatsInASpace() {
        val community = WaChat("1@g.us", name = "Outdoors", isGroup = true, isCommunity = true)
        val group =
            WaChat(
                "2@g.us",
                name = "Hiking crew",
                isGroup = true,
                communityId = "1@g.us",
                participants = listOf(WaParticipant(ME, isMe = true), WaParticipant(SAM, "15555550123")),
            )
        go.translate(WaEvent.Group(community))
        val events = go.translate(WaEvent.Group(group))
        val chat = (events[0] as ConnectorEvent.ChatUpdated).chat
        assertEquals(ChatKind.GROUP, chat.kind)
        assertEquals(account.space("1@g.us"), chat.spaceId)
        val space = (events[1] as ConnectorEvent.SpaceUpdated).space
        assertEquals("Outdoors", space.title)
        assertEquals(listOf(account.chat("2@g.us")), space.chatIds)
    }

    private companion object {
        const val SAM = "15555550123@s.whatsapp.net"
        const val ME = "15555550100@s.whatsapp.net"
    }
}
