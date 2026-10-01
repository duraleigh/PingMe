// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.Credentials
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageId
import org.pingme.core.model.MessageKind
import org.pingme.core.model.MessageStatus
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DemoNetworkTest {
    private val controls = DemoControls()
    private val demo =
        DemoConnector(controls, MemoryCredentialStore(), Files.createTempDirectory("demo").toFile(), Clock.System)
    private val account =
        Account(
            AccountId("demo-1"),
            NetworkId.DEMO,
            "Demo",
            0,
            ConnectionState.Connected,
            true,
            NotificationMode.NORMAL,
            "ref",
        )
    private val creds = Credentials("ref", DemoConnector.DEMO_SECRET)

    @Test
    fun theCastCoversWhatTheUiMustShow() =
        runBlocking {
            val chats = demo.syncChats(account.id)
            assertTrue("Enough chats to fill the pinned grid and the list", chats.size >= 12)
            assertTrue(chats.any { it.kind == ChatKind.GROUP })
            assertTrue(chats.any { it.folder == ChatFolder.GENERAL })
            assertTrue(chats.any { it.folder == ChatFolder.REQUESTS })
            assertTrue(chats.any { it.unreadCount > 0 })
            val messages = chats.flatMap { demo.syncMessages(it.id, before = null, limit = 100) }.map { it.message }
            val kinds = messages.map { it.kind }.toSet()
            assertTrue(
                kinds.containsAll(
                    listOf(MessageKind.TEXT, MessageKind.IMAGE, MessageKind.VOICE, MessageKind.GIF, MessageKind.FILE),
                ),
            )
            assertTrue(messages.any { it.replyTo != null && it.quote != null })
            assertTrue(messages.any { it.reactions.isNotEmpty() })
            assertTrue(messages.any { it.linkPreview != null })
            assertTrue(messages.any { it.isOutgoing } && messages.any { !it.isOutgoing })
        }

    @Test
    fun groupsCanBeMadeAndPeopleBlocked() =
        runBlocking {
            val group = demo.createGroup(account.id, "Book club", listOf("+15550100", "ana"))
            val made = demo.syncChats(account.id).single { it.id == group }
            assertEquals(ChatKind.GROUP, made.kind)
            assertEquals("Book club", made.title)
            assertEquals(listOf("+15550100", "ana"), made.participants.map { it.networkHandle })

            demo.block(group)
            assertTrue(demo.syncChats(account.id).none { it.id == group })

            controls.update { it.copy(capabilities = DemoControls.MINIMAL) }
            assertThrows(UnsupportedCapabilityException::class.java) {
                runBlocking { demo.createGroup(account.id, "No", listOf("x")) }
            }
            val other = demo.syncChats(account.id).first().id
            assertThrows(UnsupportedCapabilityException::class.java) { runBlocking { demo.block(other) } }
            Unit
        }

    @Test
    fun yourOwnRecentMessagesCanBeEdited() =
        runBlocking {
            val chat = demo.syncChats(account.id).first().id
            val sent =
                demo.send(
                    chat,
                    OutgoingMessage(account.id.message("draft"), "helo", emptyList(), null, null, false),
                )
            val id = (sent as SendResult.Sent).message.message.id
            demo.edit(id, "hello")
            val edited = demo.syncMessages(chat, before = null, limit = 50).map { it.message }.single { it.id == id }
            assertEquals("hello", edited.body)
            assertTrue(edited.editedAt != null)

            val theirs = demo.syncMessages(chat, before = null, limit = 50).map { it.message }.first { !it.isOutgoing }
            demo.edit(theirs.id, "not yours")
            assertEquals(
                "other people's messages are left alone",
                theirs.body,
                demo
                    .syncMessages(chat, null, 50)
                    .single {
                        it.message.id ==
                            theirs.id
                    }.message.body,
            )

            controls.update { it.copy(capabilities = DemoControls.MINIMAL) }
            assertThrows(UnsupportedCapabilityException::class.java) { runBlocking { demo.edit(id, "again") } }
            Unit
        }

    @Test
    fun everyAttachmentDownloadsToARealFile() =
        runBlocking {
            val attachments =
                demo
                    .syncChats(account.id)
                    .flatMap { demo.syncMessages(it.id, before = null, limit = 100) }
                    .flatMap { it.message.attachments }
            assertTrue(attachments.isNotEmpty())
            attachments.forEach { assertTrue(demo.downloadAttachment(it).length() > 0) }
        }

    @Test
    fun scriptedPeopleTypeThenMessageOnTheirOwn() =
        runBlocking {
            controls.update {
                it.copy(
                    liveActivity = true,
                    activityInterval = 20.milliseconds,
                    typingTime = 20.milliseconds,
                )
            }
            val events =
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        demo
                            .connect(account, creds)
                            .filter { it is ConnectorEvent.Typing || it is ConnectorEvent.NewMessage }
                            .take(2)
                            .toList()
                    }
                }
            demo.disconnect(account.id)
            val typing = events[0] as ConnectorEvent.Typing
            val message = events[1] as ConnectorEvent.NewMessage
            assertEquals(typing.chatId, message.message.message.chatId)
            assertEquals(typing.personId, message.message.message.senderId)
        }

    @Test
    fun whatYouSendGetsDeliveredAndRead() =
        runBlocking {
            controls.update { it.copy(liveActivity = true, activityInterval = 1_000.seconds) }
            val events = demo.connect(account, creds)
            val chat = account.id.chat("sam")
            val sent =
                demo.send(
                    chat,
                    OutgoingMessage(MessageId("c"), "hello", emptyList(), null, null, forceSms = false),
                ) as SendResult.Sent
            val read =
                withContext(Dispatchers.Default) {
                    withTimeout(
                        10.seconds,
                    ) { events.first { it is ConnectorEvent.ReadReceipt } as ConnectorEvent.ReadReceipt }
                }
            demo.disconnect(account.id)
            assertEquals(sent.message.message.id, read.upTo)
            val stored = demo.syncMessages(chat, before = null, limit = 5).first { it.message.id == read.upTo }.message
            assertEquals(MessageStatus.Read, stored.status)
        }
}
