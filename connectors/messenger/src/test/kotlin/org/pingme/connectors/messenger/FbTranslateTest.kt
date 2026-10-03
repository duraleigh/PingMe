// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.connectors.messenger.bridge.FbEvent
import org.pingme.connectors.messenger.bridge.FbMedia
import org.pingme.connectors.messenger.bridge.FbMessage
import org.pingme.connectors.messenger.bridge.FbReaction
import org.pingme.connectors.messenger.bridge.FbShare
import org.pingme.connectors.messenger.bridge.FbThread
import org.pingme.connectors.messenger.bridge.FbTranslate
import org.pingme.connectors.messenger.bridge.FbUser
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.AccountId
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatKind
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus

/** What the bridge reports becomes PingMe's model, the request queue included (UI_DESIGN.md 6.4). */
class FbTranslateTest {
    private val account = AccountId("acct")
    private val go = FbTranslate(account).apply { ownId = ME }

    private fun thread(
        id: String = T,
        folder: String = "inbox",
        messages: List<FbMessage> = emptyList(),
        readAt: Long = 0,
        isGroup: Boolean = false,
        title: String = "",
        moreBefore: Boolean = false,
    ) = FbThread(
        id,
        title = title,
        isGroup = isGroup,
        folder = folder,
        lastMessageAt = 5,
        readAt = readAt,
        users = listOf(FbUser(ME, "Me", isMe = true), FbUser(SAM, "Sam Ortiz")),
        messages = messages,
        moreBefore = moreBefore,
    )

    private fun text(
        id: String,
        sender: String = SAM,
        at: Long = 5,
        body: String = "hi",
    ) = FbMessage(id, T, sender, at, "text", body)

    @Test
    fun pastedCookiesAreReadFromAHeaderACurlCommandOrJson() {
        assertEquals("abc", parseCookies("xs=abc; c_user=1; datr=d")["xs"])
        assertEquals(
            "1",
            parseCookies("curl 'https://www.facebook.com/' -H 'cookie: xs=abc; c_user=1; datr=d'")["c_user"],
        )
        assertEquals("d", parseCookies("""{"xs":"abc","c_user":"1","datr":"d"}""")["datr"])
    }

    @Test
    fun aThreadBecomesAChatNamedAfterTheOtherPerson() {
        val chat = go.chat(thread())
        assertEquals(ChatKind.DIRECT, chat.kind)
        assertEquals("Sam Ortiz", chat.title)
        assertEquals(ChatFolder.PRIMARY, chat.folder)
        assertEquals(listOf("You", "Sam Ortiz"), chat.participants.map { it.displayName })
        assertEquals(account.chat(T), chat.id)
    }

    @Test
    fun whatMessengerHoldsBackIsARequest() {
        assertEquals(ChatFolder.REQUESTS, go.chat(thread(folder = "pending")).folder)
        assertEquals(ChatFolder.REQUESTS, go.chat(thread(folder = "other")).folder)
        assertEquals(ChatFolder.REQUESTS, go.chat(thread(folder = "spam")).folder)
        assertEquals(ChatFolder.PRIMARY, go.chat(thread(folder = "archived")).folder)
    }

    @Test
    fun aGroupKeepsItsNameAndEveryMember() {
        val chat = go.chat(thread(isGroup = true, title = "Weekend plans"))
        assertEquals(ChatKind.GROUP, chat.kind)
        assertEquals("Weekend plans", chat.title)
    }

    @Test
    fun unreadWhenTheOtherSideWroteAfterMyLastRead() {
        assertEquals(1, go.chat(thread(messages = listOf(text("a", at = 9)), readAt = 5)).unreadCount)
        assertEquals(0, go.chat(thread(messages = listOf(text("a", at = 9)), readAt = 9)).unreadCount)
        assertEquals(0, go.chat(thread(messages = listOf(text("a", sender = ME, at = 9)), readAt = 5)).unreadCount)
    }

    @Test
    fun aThreadEventCarriesTheChatAndItsHistory() {
        val events = go.translate(FbEvent.Thread(thread(messages = listOf(text("a"), text("b")), moreBefore = true)))
        assertTrue(events[0] is ConnectorEvent.ChatUpdated)
        val batch = events[1] as ConnectorEvent.HistoryBatch
        assertEquals(2, batch.messages.size)
        assertEquals(false, batch.complete)
        assertEquals(account.message("$T/a"), batch.messages[0].message.id)
    }

    @Test
    fun aNewMessageThenARepeatIsAnUpdate() {
        val first = go.translate(FbEvent.Message(text("m1")))
        assertTrue(first.single() is ConnectorEvent.NewMessage)
        val again = go.translate(FbEvent.Message(text("m1", body = "hi!")))
        assertTrue(again.single() is ConnectorEvent.MessageUpdated)
    }

    @Test
    fun systemRowsAreNotMessages() {
        assertTrue(go.translate(FbEvent.Message(FbMessage("s", T, SAM, 5, "system", "Sam named the group"))).isEmpty())
    }

    @Test
    fun picturesAndVoiceKeepTheirShape() {
        val picture =
            go.message(
                FbMessage(
                    "p",
                    T,
                    SAM,
                    5,
                    "image",
                    "Look",
                    media =
                        listOf(
                            FbMedia("image", "https://cdn/p.jpg", mime = "image/jpeg", width = 640, height = 480),
                        ),
                ),
            )
        assertEquals(MessageKind.IMAGE, picture.message.kind)
        assertEquals(
            AttachmentKind.IMAGE,
            picture.message.attachments
                .single()
                .kind,
        )
        assertEquals(
            640,
            picture.message.attachments
                .single()
                .width,
        )
        assertEquals("Look", picture.message.body)

        val voice =
            go.message(
                FbMessage(
                    "v",
                    T,
                    SAM,
                    5,
                    "voice",
                    media = listOf(FbMedia("voice", "https://cdn/v.mp4", mime = "audio/mp4", durationMs = 4200)),
                ),
            )
        assertEquals(MessageKind.VOICE, voice.message.kind)
        assertEquals(
            4200L,
            voice.message.attachments
                .single()
                .durationMs,
        )
    }

    @Test
    fun filesAndSharesKeepTheirShape() {
        val file =
            go.message(
                FbMessage(
                    "f",
                    T,
                    SAM,
                    5,
                    "file",
                    media =
                        listOf(
                            FbMedia(
                                "file",
                                "https://cdn/x.pdf",
                                mime = "application/pdf",
                                fileName = "x.pdf",
                                size = 99,
                            ),
                        ),
                ),
            )
        assertEquals(MessageKind.FILE, file.message.kind)
        assertEquals(
            "x.pdf",
            file.message.attachments
                .single()
                .fileName,
        )
        assertEquals(
            99L,
            file.message.attachments
                .single()
                .sizeBytes,
        )

        val share =
            go.message(
                FbMessage(
                    "s",
                    T,
                    SAM,
                    5,
                    "share",
                    share = FbShare("A page", "news.example", "https://news.example/story"),
                ),
            )
        assertEquals("https://news.example/story", share.message.body)
        assertEquals("A page", share.message.linkPreview?.title)
    }

    @Test
    fun repliesReactionsEditsAndUnsendsFollow() {
        val reply =
            go.message(
                FbMessage(
                    "r",
                    T,
                    SAM,
                    5,
                    "text",
                    "yes",
                    replyTo = "q",
                    replyText = "coming?",
                    reactions = listOf(FbReaction("❤️", ME, 6)),
                ),
            )
        assertEquals(account.message("$T/q"), reply.message.replyTo)
        assertEquals("coming?", reply.message.quote?.text)
        assertEquals(
            "❤️",
            reply.message.reactions
                .single()
                .emoji,
        )

        val reaction =
            go
                .translate(
                    FbEvent.Reaction("", "r", removed = true, reaction = FbReaction("❤️", ME, 7)),
                ).single()
        assertEquals(account.message("$T/r"), (reaction as ConnectorEvent.ReactionChanged).messageId)
        assertTrue(reaction.removed)

        val edit = go.translate(FbEvent.Edit("0", "r", "yes!", 8)).single() as ConnectorEvent.MessageEdited
        assertEquals(account.message("$T/r"), edit.messageId)
        assertEquals("yes!", edit.body)

        val unsent = go.translate(FbEvent.Unsent(T, "r")).single()
        assertTrue(unsent is ConnectorEvent.MessageRevoked)
        assertNull(go.seen(account.message("$T/r")))
    }

    @Test
    fun theOtherSideReadingMarksMyMessagesRead() {
        go.message(text("mine", sender = ME, at = 5))
        go.message(text("later", sender = ME, at = 9))
        val events = go.translate(FbEvent.ReadReceipt(T, SAM, 6))
        val changed = events.single() as ConnectorEvent.StatusChanged
        assertEquals(account.message("$T/mine"), changed.messageId)
        assertEquals(MessageStatus.Read, changed.status)
        assertTrue(go.translate(FbEvent.ReadReceipt(T, SAM, 6)).isEmpty())
    }

    @Test
    fun myOwnReadMarkerClearsTheUnreadCount() {
        go.chat(thread(messages = listOf(text("a", at = 9)), readAt = 5))
        val updated = go.translate(FbEvent.ReadByMe(T, 9)).single() as ConnectorEvent.ChatUpdated
        assertEquals(0, updated.chat.unreadCount)
    }

    @Test
    fun typingAndGoneThreadsPassThrough() {
        val typing = go.translate(FbEvent.Typing(T, SAM, typing = true)).single() as ConnectorEvent.Typing
        assertEquals(account.chat(T), typing.chatId)
        val gone = go.translate(FbEvent.ThreadGone(T)).single()
        assertTrue(gone is ConnectorEvent.ChatRemoved)
    }

    companion object {
        const val ME = "100"
        const val SAM = "200"
        const val T = "200"
    }
}
