// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.fbpage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.AccountId
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageKind
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/** Graph API answers become PingMe's model, and Meta's rules (the token, the 24-hour window) hold. */
class FbPageTranslateTest {
    private val account = AccountId("acct")
    private val go = FbPageTranslate(account, PAGE, "Duraleigh Page")

    private fun sam(
        id: String,
        text: String,
        at: Long,
        attachments: List<PageAttachment> = emptyList(),
        shares: List<PageShare> = emptyList(),
    ) = PageMessage(id, at, PageUser(SAM, "Sam Ortiz"), text, attachments, shares, "", false)

    private fun conversation(vararg messages: PageMessage) =
        PageConversation(
            T,
            messages.maxOf {
                it.createdAt
            },
            listOf(PageUser(SAM, "Sam Ortiz"), PageUser(PAGE, "Duraleigh Page")),
            messages.toList(),
        )

    @Test
    fun metaTimesAreRead() {
        assertEquals(1_790_982_904_000L, PageGraph.parseTime("2026-10-02T23:15:04+0000"))
        assertEquals(0L, PageGraph.parseTime("yesterday"))
    }

    @Test
    fun aConversationIsADirectChatWithThePerson() {
        val chat = go.chat(conversation(sam("m1", "hi", 5)))
        assertEquals(ChatKind.DIRECT, chat.kind)
        assertEquals("Sam Ortiz", chat.title)
        assertEquals(listOf("You", "Sam Ortiz"), chat.participants.map { it.displayName })
        assertEquals(1, chat.unreadCount)
        assertEquals(account.chat(T), chat.id)
    }

    @Test
    fun onlyUnseenMessagesAreFresh() {
        val first = go.fresh(conversation(sam("m1", "one", 5), sam("m2", "two", 6)))
        assertEquals(listOf("one", "two"), first.map { it.message.body })
        val again = go.fresh(conversation(sam("m1", "one", 5), sam("m2", "two", 6), sam("m3", "three", 7)))
        assertEquals(listOf("three"), again.map { it.message.body })
        assertTrue(go.changed(conversation(sam("m4", "four", 8))))
        go.chat(conversation(sam("m4", "four", 8)))
        assertTrue(!go.changed(conversation(sam("m4", "four", 8))))
    }

    @Test
    fun picturesVideosGifsAndFilesKeepTheirKind() {
        val picture = PageAttachment("a1", "p.jpg", "image/jpeg", "https://cdn/p.jpg", "", 640, 480, 0, "image")
        val video = PageAttachment("a2", "v.mp4", "video/mp4", "https://cdn/v.mp4", "", 0, 0, 4200, "video")
        val gif = PageAttachment("a3", "g.gif", "image/gif", "https://cdn/g.gif", "", 0, 0, 0, "gif")
        val pdf = PageAttachment("a4", "x.pdf", "application/pdf", "https://cdn/x.pdf", "", 0, 0, 0, "file")
        assertEquals(MessageKind.IMAGE, go.message(T, sam("m1", "", 5, listOf(picture))).message.kind)
        assertEquals(
            640,
            go
                .message(T, sam("m1", "", 5, listOf(picture)))
                .message.attachments
                .single()
                .width,
        )
        val clip = go.message(T, sam("m2", "", 5, listOf(video))).message
        assertEquals(MessageKind.VIDEO, clip.kind)
        assertEquals(4200L, clip.attachments.single().durationMs)
        assertEquals(
            AttachmentKind.GIF,
            go
                .message(T, sam("m3", "", 5, listOf(gif)))
                .message.attachments
                .single()
                .kind,
        )
        val file = go.message(T, sam("m4", "", 5, listOf(pdf))).message
        assertEquals(MessageKind.FILE, file.kind)
        assertEquals("x.pdf", file.attachments.single().fileName)
        assertEquals("https://cdn/x.pdf", file.attachments.single().remoteRef)
    }

    @Test
    fun sharesBecomeLinkCards() {
        val share =
            go
                .message(
                    T,
                    sam("s", "", 5, shares = listOf(PageShare("https://news.example/story", "A page", "news"))),
                ).message
        assertEquals("https://news.example/story", share.body)
        assertEquals("A page", share.linkPreview?.title)
    }

    @Test
    fun thePagesOwnMessagesAreOutgoing() {
        val mine =
            go.message(
                T,
                PageMessage("o", 5, PageUser(PAGE, "Duraleigh Page"), "Thanks!", emptyList(), emptyList(), "", false),
            )
        assertTrue(mine.message.isOutgoing)
        assertEquals("You", mine.sender?.displayName)
        assertNull(go.lastFromPerson(T))
        go.message(T, sam("in", "hello", 9))
        assertEquals(9L, go.lastFromPerson(T))
    }

    @Test
    fun aGoodTokenConnectsThePageAndABadOneIsRefused() =
        runTest {
            val api = FakePageApi()
            val connector =
                FbPageConnector(api, MemoryCredentialStore(), Files.createTempDirectory("p").toFile(), 100.milliseconds)
            val flow = connector.loginFlow()
            var refused = false
            val done =
                withContext(Dispatchers.Default) {
                    flow.steps.first { step ->
                        when (step) {
                            is LoginStep.Done -> {
                                true
                            }

                            is LoginStep.EnterText -> {
                                if (step.error != null) refused = true
                                val token = if (step.error == null) "wrong" else FakePageApi.TOKEN
                                flow.respond(
                                    step.id,
                                    LoginResponse.Text(token),
                                )
                                false
                            }

                            is LoginStep.Failed -> {
                                throw AssertionError(step.reason)
                            }

                            else -> {
                                false
                            }
                        }
                    }
                } as LoginStep.Done
            assertTrue(refused)
            assertEquals("fbpage/${FakePageApi.PAGE}", done.credentialRef)
            assertEquals("Page: Duraleigh Page", done.accountName)
        }

    @Test
    fun repliesAreRefusedOnceTheDayHasPassed() =
        runTest {
            val stale = FbPageTranslate(account, PAGE, "Duraleigh Page")
            stale.chat(conversation(sam("old", "hi", System.currentTimeMillis() - 25 * 60 * 60 * 1000L)))
            val api = FakePageApi()
            val session =
                FbPageSession(account, PageGraph(api), FakePageApi.TOKEN, PAGE, "Duraleigh Page", 100.milliseconds)
            session.go.chat(conversation(sam("old", "hi", System.currentTimeMillis() - 25 * 60 * 60 * 1000L)))
            val draft =
                OutgoingMessage(
                    account.message("x"),
                    "late",
                    emptyList(),
                    null,
                    null,
                    false,
                )
            val result = session.send(account.chat(T), draft) {}
            assertTrue(result is SendResult.Failed)
            assertEquals(FbPageSession.WINDOW_CLOSED, (result as SendResult.Failed).reason)
            assertTrue(api.sentTexts.isEmpty())
        }

    @Test
    fun pollingReportsCheckedTimes() =
        runTest {
            val api = FakePageApi()
            val session =
                FbPageSession(account, PageGraph(api), FakePageApi.TOKEN, PAGE, "Duraleigh Page", 50.milliseconds)
            val seen = CopyOnWriteArrayList<ConnectorEvent>()
            withContext(Dispatchers.Default) {
                val job = launch { session.flow().collect { seen += it } }
                delay(400)
                session.close()
                job.join()
            }
            val first = seen.first() as ConnectorEvent.State
            assertEquals(ConnectionState.Connected, first.state)
            assertTrue(seen.any { it is ConnectorEvent.State && it.state is ConnectionState.Polled })
            assertTrue(seen.any { it is ConnectorEvent.ChatUpdated })
            assertEquals(3, seen.count { it is ConnectorEvent.NewMessage })
        }

    companion object {
        const val PAGE = "1000"
        const val SAM = "200"
        const val T = "t_1"
    }
}
