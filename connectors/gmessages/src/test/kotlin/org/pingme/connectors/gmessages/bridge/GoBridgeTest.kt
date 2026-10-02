// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.connector.person
import org.pingme.core.model.AccountId
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatKind
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.Transport
import java.io.File

/** GoBridge against the session the Go tests recorded (gobridge/gm/testdata/session.json). */
class GoBridgeTest {
    private val account = AccountId("acct")
    private val bridge = GoBridge(account)
    private val fixture = SessionFixture.load()

    @Test
    fun conversationsBecomeChatsWithPeople() {
        val page = bridge.conversationPage(fixture.conversationsJson)
        val chats = page.conversations.map(bridge::chat)
        val sam = chats.first { it.networkRemoteId == "12" }
        assertEquals(account.chat("12"), sam.id)
        assertEquals(ChatKind.DIRECT, sam.kind)
        assertEquals("Sam Ortiz", sam.title)
        assertEquals(1, sam.unreadCount)
        assertEquals(listOf("You", "Sam Ortiz"), sam.participants.map { it.displayName })
        assertEquals("+15555550123", sam.participants[1].phoneNumber)
        val group = chats.first { it.networkRemoteId == "34" }
        assertEquals(ChatKind.GROUP, group.kind)
        assertEquals("Hiking crew", group.title)
        assertEquals("2", bridge.outgoingId("12"))
        assertEquals(true, bridge.isRcs("12"))
        assertEquals(false, bridge.isRcs("34"))
    }

    @Test
    fun messagesKeepTextMediaReactionsAndStatus() {
        bridge.conversationPage(fixture.conversationsJson).conversations.forEach(bridge::chat)
        val page = bridge.messagePage(fixture.messagesJson("12"))
        assertEquals(3, page.messages.size)
        val incoming = bridge.message(page.messages[0])
        assertEquals(account.message("1003"), incoming.message.id)
        assertEquals("See you at 7\nBring the map", incoming.message.body)
        assertEquals(MessageKind.IMAGE, incoming.message.kind)
        assertEquals(Transport.RCS, incoming.message.transport)
        assertEquals(false, incoming.message.isOutgoing)
        assertEquals("Sam Ortiz", incoming.sender?.displayName)
        val attachment = incoming.message.attachments.single()
        assertEquals(AttachmentKind.IMAGE, attachment.kind)
        assertEquals("map.jpg", attachment.fileName)
        assertEquals(1200, attachment.width)
        val ref = GoBridge.MediaRef.decode(attachment.remoteRef!!)
        assertEquals("media-abc", ref.mediaId)
        assertTrue(ref.key.isNotEmpty())
        assertEquals(listOf("❤️"), incoming.message.reactions.map { it.emoji })
        assertEquals(
            account.person("3"),
            incoming.message.reactions
                .single()
                .senderId,
        )

        val outgoing = bridge.message(page.messages[1])
        assertEquals(true, outgoing.message.isOutgoing)
        assertEquals(MessageStatus.Read, outgoing.message.status)
        assertEquals(account.message("1001"), outgoing.message.replyTo)
        assertEquals(account.person("2"), outgoing.message.senderId)

        val older = bridge.message(page.messages[2])
        assertEquals(Transport.SMS, older.message.transport)
        assertEquals(MessageKind.TEXT, older.message.kind)
    }

    @Test
    fun twoConversationsWithOnePersonAreOneChat() {
        // The phone keeps an RCS and an SMS conversation for Sam; PingMe shows one thread (owner, Gate G3).
        val page = bridge.conversationPage(fixture.conversationsJson)
        val rcs = page.conversations.first { it.id == "12" }
        val sms = rcs.copy(id = "56", type = "SMS", unread = false, lastMessageAt = rcs.lastMessageAt + 1_000_000)
        val first = bridge.chat(rcs)
        val second = bridge.chat(sms)
        assertEquals(account.chat("12"), first.id)
        assertEquals(account.chat("12"), second.id)
        assertEquals("one is unread, so the chat is", 1, second.unreadCount)
        assertEquals(sms.lastMessageAt / 1000, second.lastActivityAt.toEpochMilliseconds())
        assertEquals(setOf("12", "56"), bridge.group("12"))
        assertEquals(listOf("56"), bridge.aliases())
        // Sends go where the talk is: the SMS conversation had the newer message.
        assertEquals("56", bridge.liveConversation("12"))
        // A message in the SMS conversation lands in the same chat.
        val smsText =
            bridge
                .messagePage(
                    fixture.messagesJson("12"),
                ).messages[2]
                .copy(id = "9001", conversationId = "56")
        assertEquals(account.chat("12"), bridge.message(smsText).message.chatId)
        // Typing in either conversation shows in the one chat.
        val typing = bridge.translate(GmEvent.Typing("56", "+15555550123", typing = true)).single()
        assertEquals(account.chat("12"), (typing as ConnectorEvent.Typing).chatId)
        // Groups never fold, and nor do chats with different numbers.
        val group = page.conversations.first { it.id == "34" }
        assertEquals(account.chat("34"), bridge.chat(group).id)
    }

    @Test
    fun whenTheShownConversationGoesTheChatMovesToTheOther() {
        val page = bridge.conversationPage(fixture.conversationsJson)
        val rcs = page.conversations.first { it.id == "12" }
        val sms = rcs.copy(id = "56", type = "SMS")
        bridge.chat(rcs)
        bridge.chat(sms)
        val events = bridge.translate(GmEvent.Conversation(rcs.copy(status = "DELETED")))
        assertEquals(account.chat("12"), (events[0] as ConnectorEvent.ChatRemoved).chatId)
        assertEquals(account.chat("56"), (events[1] as ConnectorEvent.ChatUpdated).chat.id)
        assertEquals("56", bridge.canonical("56"))
    }

    @Test
    fun thePhonesDownloadingNoteIsNotTextAndAudioIsAVoiceMessage() {
        bridge.conversationPage(fixture.conversationsJson).conversations.forEach(bridge::chat)
        val page = bridge.messagePage(fixture.messagesJson("12"))
        val downloading = page.messages[0].copy(text = "", pendingDownload = "Downloading message...")
        assertNull(bridge.message(downloading).message.body)
        val failed = page.messages[0].copy(text = "", pendingDownload = "Message download failed")
        assertEquals("Message download failed", bridge.message(failed).message.body)
        val audio =
            page.messages[0].copy(
                id = "1004",
                media = listOf(page.messages[0].media[0].copy(mime = "audio/mp4")),
            )
        assertEquals(MessageKind.VOICE, bridge.message(audio).message.kind)
        assertEquals(
            AttachmentKind.AUDIO,
            bridge
                .message(audio)
                .message.attachments
                .single()
                .kind,
        )
    }

    @Test
    fun eventsBecomeConnectorEvents() {
        val events = fixture.events.map { bridge.parse(it) }
        val translated = events.flatMap { bridge.translate(it) }
        val ready = events.filterIsInstance<GmEvent.Ready>().single()
        assertEquals(false, ready.resync)
        val newMessage = translated.filterIsInstance<ConnectorEvent.NewMessage>().single()
        assertEquals(account.message("1003"), newMessage.message.message.id)
        // The chat event came after the message in the fixture, so typing resolved through the chat
        // event; the message itself resolved its sender from the message's own sender entry.
        assertEquals("Sam Ortiz", newMessage.message.sender?.displayName)
        // The typing event precedes the conversation event in the recording, so its number
        // cannot be resolved to a person yet and it is dropped rather than guessed.
        assertTrue(translated.none { it is ConnectorEvent.Typing })
        assertEquals(1, translated.filterIsInstance<ConnectorEvent.ChatUpdated>().size)
        assertTrue(translated.none { it is ConnectorEvent.ReactionChanged })
    }

    @Test
    fun aRepeatedMessageIsAnUpdateAndNewReactionsFlip() {
        val first = bridge.parse(fixture.events.first { "\"message\"" in it })
        val original = (first as GmEvent.Message).message
        bridge.translate(first)
        val reacted = original.copy(reactions = original.reactions + GmReaction("🔥", listOf("3")))
        val again = bridge.translate(GmEvent.Message(reacted))
        assertTrue(again[0] is ConnectorEvent.MessageUpdated)
        val flips = again.filterIsInstance<ConnectorEvent.ReactionChanged>()
        assertEquals(listOf("🔥"), flips.map { it.reaction.emoji })
        assertEquals(false, flips.single().removed)
        val removed = bridge.translate(GmEvent.Message(original))
        assertEquals(listOf(true), removed.filterIsInstance<ConnectorEvent.ReactionChanged>().map { it.removed })
    }

    @Test
    fun deletedAndHiddenMessages() {
        val msg = (bridge.parse(fixture.events.first { "\"message\"" in it }) as GmEvent.Message).message
        val gone = bridge.translate(GmEvent.Message(msg.copy(direction = "deleted", status = 300)))
        assertTrue(gone.single() is ConnectorEvent.MessageRemoved)
        assertTrue(bridge.translate(GmEvent.Message(msg.copy(hide = true))).isEmpty())
    }

    @Test
    fun typingNeedsAKnownChat() {
        val typing = GmEvent.Typing("12", "+15555550123", typing = true)
        assertTrue(bridge.translate(typing).isEmpty())
        bridge.conversationPage(fixture.conversationsJson).conversations.forEach(bridge::chat)
        val event = bridge.translate(typing).single() as ConnectorEvent.Typing
        assertEquals(account.person("3"), event.personId)
    }

    @Test
    fun myReactionComesFromSimIds() {
        bridge.translate(bridge.parse(fixture.events.first { "\"settings\"" in it }))
        val msg = (bridge.parse(fixture.events.first { "\"message\"" in it }) as GmEvent.Message).message
        bridge.translate(GmEvent.Message(msg.copy(reactions = listOf(GmReaction("👍", listOf("2", "3"))))))
        assertEquals("👍", bridge.myReaction(msg.id))
        assertNull(bridge.myReaction("nope"))
    }

    @Test
    fun aGoneConversationRemovesTheChat() {
        val conv = bridge.conversationPage(fixture.conversationsJson).conversations.first()
        val removed = bridge.translate(GmEvent.Conversation(conv.copy(status = "DELETED"))).single()
        assertTrue(removed is ConnectorEvent.ChatRemoved)
        assertNotNull(bridge.translate(GmEvent.Conversation(conv)).single() as? ConnectorEvent.ChatUpdated)
    }

    @Test
    fun systemNotesAreNeverBubbles() {
        val msg = (bridge.parse(fixture.events.first { "\"message\"" in it }) as GmEvent.Message).message
        val note =
            msg.copy(
                id = "9001",
                direction = "tombstone",
                statusName = "TOMBSTONE_PROTOCOL_SWITCH_TO_RCS",
                text = "",
            )
        assertTrue(bridge.translate(GmEvent.Message(note)).isEmpty())
        assertEquals(false, bridge.isShown(note))
        assertEquals(true, bridge.isShown(msg))
    }

    @Test
    fun aTickNeverGoesBackwards() {
        val page = bridge.messagePage(fixture.messagesJson("12"))
        val read = page.messages[1] // outgoing, OUTGOING_DISPLAYED
        assertEquals(MessageStatus.Read, bridge.message(read).message.status)
        val older = read.copy(read = false, delivered = false, sent = true, statusName = "OUTGOING_COMPLETE")
        assertEquals(MessageStatus.Read, bridge.message(older).message.status)
        val failed = read.copy(failed = true, failReason = "Sending failed")
        assertTrue(bridge.message(failed).message.status is MessageStatus.Failed)
    }

    @Test
    fun errorCodesComeBeforeTheColon() {
        assertEquals(GmError.LOGGED_OUT, GmError.codeOf(Exception("LOGGED_OUT: Google said no")))
        assertNull(GmError.codeOf(Exception("something else happened")))
        assertNull(GmError.codeOf(Exception("http 401: nope")))
    }
}

/**
 * Reads gobridge/gm/testdata/session.json, which the Go tests write. Unit tests run with
 * the module directory as their working directory, so the file is found by walking up.
 */
class SessionFixture private constructor(
    private val root: JsonElement,
) {
    val conversationsJson: String get() = root.jsonObject.getValue("conversations").toString()

    fun messagesJson(conversationId: String): String =
        root.jsonObject
            .getValue("messages")
            .jsonObject
            .getValue(conversationId)
            .toString()

    val events: List<String> get() =
        root.jsonObject
            .getValue("events")
            .jsonArray
            .map { it.toString() }

    companion object {
        fun load(): SessionFixture {
            val file =
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .map { it.resolve("gobridge/gm/testdata/session.json") }
                    .firstOrNull { it.exists() }
            checkNotNull(file) { "gobridge/gm/testdata/session.json missing: run `go test ./gm -update` in gobridge" }
            return SessionFixture(gmJson.parseToJsonElement(file.readText()))
        }
    }
}
