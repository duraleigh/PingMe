// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.connectors.instagram.bridge.IgEvent
import org.pingme.connectors.instagram.bridge.IgMedia
import org.pingme.connectors.instagram.bridge.IgMessage
import org.pingme.connectors.instagram.bridge.IgReaction
import org.pingme.connectors.instagram.bridge.IgShare
import org.pingme.connectors.instagram.bridge.IgThread
import org.pingme.connectors.instagram.bridge.IgTranslate
import org.pingme.connectors.instagram.bridge.IgUser
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.AccountId
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus

/** What the bridge reports becomes PingMe's model, folders included (UI_DESIGN.md 6.4). */
class IgTranslateTest {
    private val account = AccountId("acct")
    private val go = IgTranslate(account).apply { ownId = ME }

    private fun thread(
        id: String = T,
        systemFolder: String = "",
        folder: String = "",
        messages: List<IgMessage> = emptyList(),
        readAt: Long = 0,
        markedUnread: Boolean = false,
    ) = IgThread(
        id,
        longId = "${id}L",
        systemFolder = systemFolder,
        folder = folder,
        lastMessageAt = 5,
        readAt = readAt,
        markedUnread = markedUnread,
        users = listOf(IgUser(SAM, "9", "sam", "Sam Ortiz"), IgUser(ME, "8", "me", "Me", isMe = true)),
        messages = messages,
    )

    private fun text(
        id: String,
        sender: String = SAM,
        at: Long = 5,
        body: String = "hi",
    ) = IgMessage(id, T, sender, at, "text", body)

    @Test
    fun pastedCookiesAreReadFromAHeaderACurlCommandOrJson() {
        assertEquals("abc", parseCookies("sessionid=abc; csrftoken=x; ds_user_id=1")["sessionid"])
        assertEquals(
            "1",
            parseCookies(
                """curl 'https://www.instagram.com/' -H 'cookie: ds_user_id=1; sessionid=abc'""",
            )["ds_user_id"],
        )
        assertEquals("abc", parseCookies("""{"sessionid":"abc","csrftoken":"x"}""")["sessionid"])
        assertEquals(emptyMap<String, String>(), parseCookies("nothing here"))
    }

    @Test
    fun aThreadHandedOverWithoutItsFolderKeepsTheOneItWasListedWith() {
        // Carrie sat in General; her next message moved her to Primary (owner, 2026-10-06).
        assertEquals(ChatFolder.GENERAL, go.chat(thread(folder = "GENERAL")).folder)
        val bare = go.chat(thread(messages = listOf(text("m1"))))
        assertEquals(ChatFolder.GENERAL, bare.folder)
        // A thread never listed with a folder does not claim one.
        assertEquals(null, go.chat(thread(id = "other")).folder)
        // A thread fetched on its own says only system='INBOX': not a request, folder still unknown
        // (owner, 2026-10-07: General chats moved to Primary at every new message).
        assertEquals(null, go.chat(thread(id = "fetched", systemFolder = "INBOX")).folder)
        assertEquals(ChatFolder.GENERAL, go.chat(thread(folder = "GENERAL")).folder)
        assertEquals(ChatFolder.GENERAL, go.chat(thread(systemFolder = "INBOX")).folder)
        assertEquals(ChatFolder.REQUESTS, go.chat(thread(id = "req", systemFolder = "PENDING")).folder)
    }

    @Test
    fun foldersFollowInstagramsNames() {
        assertEquals(ChatFolder.REQUESTS, IgTranslate.folderOf("PENDING", "", ""))
        assertEquals(ChatFolder.REQUESTS, IgTranslate.folderOf("SPAM", "", ""))
        assertEquals(ChatFolder.GENERAL, IgTranslate.folderOf("", "GENERAL", ""))
        assertEquals(ChatFolder.GENERAL, IgTranslate.folderOf("", "", "general_folder"))
        assertEquals(ChatFolder.PRIMARY, IgTranslate.folderOf("", "PRIMARY", ""))
    }

    @Test
    fun aThreadBecomesAChatWithItsPeopleUnreadAndHistory() {
        val events =
            go.translate(
                IgEvent.Thread(
                    thread(messages = listOf(text("m2", at = 5), text("m1", sender = ME, at = 4)), readAt = 4),
                ),
            )
        val chat = (events[0] as ConnectorEvent.ChatUpdated).chat
        assertEquals(account.chat(T), chat.id)
        assertEquals("Sam Ortiz", chat.title)
        assertEquals(listOf("You", "Sam Ortiz"), chat.participants.map { it.displayName })
        assertEquals("a message from Sam after my last read", 1, chat.unreadCount)
        // No folder fields: the folder is unknown, not Primary (the inbox shows unknown under Primary).
        assertEquals(null, chat.folder)
        val batch = events[1] as ConnectorEvent.HistoryBatch
        assertEquals(listOf("m2", "m1"), batch.messages.map { it.message.networkRemoteId })
        assertTrue(batch.messages[1].message.isOutgoing)
        assertEquals(MessageStatus.Sent, batch.messages[1].message.status)
        // Read it all: no longer unread.
        val read = go.translate(IgEvent.ReadByMe(T, 6)).single() as ConnectorEvent.ChatUpdated
        assertEquals(0, read.chat.unreadCount)
    }

    @Test
    fun mediaSharesReactionsAndUnsendsTranslate() {
        go.translate(IgEvent.Thread(thread()))
        val picture =
            text(
                "p1",
            ).copy(
                kind = "image",
                text = "",
                media = listOf(IgMedia("image", "https://cdn/a.jpg", width = 800, height = 600)),
            )
        val message = (go.translate(IgEvent.Message(picture)).single() as ConnectorEvent.NewMessage).message.message
        assertEquals(MessageKind.IMAGE, message.kind)
        assertEquals(AttachmentKind.IMAGE, message.attachments.single().kind)
        assertEquals(800, message.attachments.single().width)
        val share =
            text(
                "s1",
            ).copy(kind = "share", text = "", share = IgShare("A reel", "by sam", "https://instagram.com/reel/1"))
        val shared = go.message(share).message
        assertEquals("https://instagram.com/reel/1", shared.body)
        assertEquals("A reel", shared.linkPreview?.title)
        val reaction =
            go
                .translate(
                    IgEvent.Reaction(T, "p1", false, IgReaction("❤️", SAM, 7)),
                ).single() as ConnectorEvent.ReactionChanged
        assertEquals(account.message("$T/p1"), reaction.messageId)
        val gone = go.translate(IgEvent.Unsent(T, "p1")).single()
        assertEquals(account.message("$T/p1"), (gone as ConnectorEvent.MessageRevoked).messageId)
        val voice =
            text(
                "v1",
            ).copy(
                kind = "voice",
                text = "",
                media = listOf(IgMedia("voice", "https://cdn/v.m4a", mime = "audio/mp4", durationMs = 4200)),
            )
        assertEquals(
            4200L,
            go
                .message(voice)
                .message.attachments
                .single()
                .durationMs,
        )
    }

    @Test
    fun theOtherSidesReadReceiptTicksYourMessagesUpToIt() {
        go.translate(IgEvent.Thread(thread()))
        go.translate(IgEvent.Message(text("o1", sender = ME, at = 5)))
        go.translate(IgEvent.Message(text("o2", sender = ME, at = 9)))
        val ticks = go.translate(IgEvent.ReadReceipt(T, SAM, 7)).filterIsInstance<ConnectorEvent.StatusChanged>()
        assertEquals(listOf(account.message("$T/o1")), ticks.map { it.messageId })
        assertEquals(MessageStatus.Read, ticks.single().status)
    }

    @Test
    fun aRequestMovesToPrimaryWhenItsFolderChanges() {
        val chat =
            (
                go.translate(
                    IgEvent.Thread(thread(systemFolder = "PENDING")),
                )[0] as ConnectorEvent.ChatUpdated
            ).chat
        assertEquals(ChatFolder.REQUESTS, chat.folder)
        val moved = go.translate(IgEvent.Folder(T, "INBOX", "PRIMARY")).single() as ConnectorEvent.ChatUpdated
        assertEquals(ChatFolder.PRIMARY, moved.chat.folder)
    }

    private companion object {
        const val T = "340282366841710300949128000"
        const val SAM = "200"
        const val ME = "100"
    }
}
