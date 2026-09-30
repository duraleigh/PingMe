// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector.contract

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.pingme.core.connector.Connector
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.Credentials
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.connector.accountId
import org.pingme.core.model.Account
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageId
import org.pingme.core.model.ReactionRule
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The contract every connector meets (BUILD_PLAN.md P1.3). A connector's test class
 * extends this and supplies a [Harness] around its fake transport; the tests here then
 * exercise login, sync, send, react, delete, and the event stream against it.
 *
 * Operations a network cannot do must throw UnsupportedCapabilityException, and the
 * tests check that too, so every test asserts something for every connector.
 */
abstract class ConnectorContractTest {
    /** A connector wired to a fresh fake transport. Called once per test. */
    protected abstract fun newHarness(): Harness

    /** The test's handle on the connector and its fake network. */
    interface Harness {
        val connector: Connector

        /** Answers a login step the way a user would; null when the step needs no answer. */
        fun answer(step: LoginStep): LoginResponse?

        /** The account a finished login creates, as the app would save it. */
        fun accountFor(done: LoginStep.Done): Account

        /** The credentials the connector saved under [LoginStep.Done.credentialRef]. */
        suspend fun credentialsFor(done: LoginStep.Done): Credentials

        /** A chat on the fake network with at least three messages of history. */
        suspend fun chatWithHistory(account: Account): ChatId

        /** Someone else sends [text] into [chatId]. */
        suspend fun receiveText(
            chatId: ChatId,
            text: String,
        )

        /** Someone else starts typing in [chatId]. Only called when typing is supported. */
        suspend fun startRemoteTyping(chatId: ChatId)

        /** Texts the fake network received from PingMe in [chatId], oldest first. */
        suspend fun textsSentTo(chatId: ChatId): List<String>

        /** Emoji currently on [messageId] on the fake network. */
        suspend fun reactionsOn(messageId: MessageId): List<String>

        /** Messages the fake network deleted for everyone. */
        suspend fun deletedForEveryone(): Set<MessageId>
    }

    /** A logged-in, connected session with its events buffered. */
    protected class Session(
        val harness: Harness,
        val account: Account,
        val events: Channel<ConnectorEvent>,
    ) {
        val connector get() = harness.connector

        /** Waits (in real time, since connectors run on their own threads) for a matching event. */
        suspend fun <T : ConnectorEvent> await(
            type: Class<T>,
            matches: (T) -> Boolean = { true },
        ): T =
            withContext(Dispatchers.Default) {
                withTimeout(EVENT_TIMEOUT) {
                    var found: T? = null
                    while (found == null) {
                        val event = events.receive()
                        if (type.isInstance(event)) found = type.cast(event)?.takeIf(matches)
                    }
                    checkNotNull(found)
                }
            }
    }

    private suspend fun login(harness: Harness): LoginStep.Done {
        val flow = harness.connector.loginFlow()
        val last =
            withContext(Dispatchers.Default) {
                withTimeout(EVENT_TIMEOUT) {
                    flow.steps.first { step ->
                        when (step) {
                            is LoginStep.Done -> {
                                true
                            }

                            is LoginStep.Failed -> {
                                throw AssertionError("Login failed: ${step.reason}")
                            }

                            else -> {
                                harness.answer(step)?.let { flow.respond(step.id, it) }
                                false
                            }
                        }
                    }
                }
            }
        return last as LoginStep.Done
    }

    private suspend fun CoroutineScope.connected(): Session {
        val harness = newHarness()
        val done = login(harness)
        val account = harness.accountFor(done)
        val events = Channel<ConnectorEvent>(Channel.UNLIMITED)
        val flow = harness.connector.connect(account, harness.credentialsFor(done))
        launch(Dispatchers.Default) { flow.collect { events.send(it) } }
        return Session(harness, account, events)
    }

    private fun draft(text: String) =
        OutgoingMessage(MessageId(UUID.randomUUID().toString()), text, emptyList(), null, null, forceSms = false)

    private suspend fun Session.sendText(
        chatId: ChatId,
        text: String,
    ) = when (val result = connector.send(chatId, draft(text))) {
        is SendResult.Sent -> result.message.message
        is SendResult.Failed -> throw AssertionError("Send failed: ${result.reason}")
    }

    @Test
    fun loginEndsWithSavedCredentials() =
        runTest {
            val harness = newHarness()
            val done = login(harness)
            assertTrue(done.credentialRef.isNotBlank())
            assertEquals(harness.connector.network, harness.accountFor(done).network)
            assertEquals(done.credentialRef, harness.credentialsFor(done).ref)
        }

    @Test
    fun connectingReportsConnected() =
        runTest {
            val s = backgroundScope.connected()
            s.await(ConnectorEvent.State::class.java) { it.state == ConnectionState.Connected }
        }

    @Test
    fun syncedChatsBelongToTheAccount() =
        runTest {
            val s = backgroundScope.connected()
            val chats = s.connector.syncChats(s.account.id)
            assertTrue("A fake network has chats", chats.isNotEmpty())
            assertEquals(chats.size, chats.map { it.id }.toSet().size)
            chats.forEach {
                assertEquals(s.account.id, it.accountId)
                assertEquals(s.account.id, it.id.accountId)
            }
        }

    @Test
    fun historyPagesBackwardsNewestFirst() =
        runTest {
            val s = backgroundScope.connected()
            val chat = s.harness.chatWithHistory(s.account)
            val first = s.connector.syncMessages(chat, before = null, limit = 2)
            assertEquals(2, first.size)
            assertTrue(first[0].message.sentAt >= first[1].message.sentAt)
            val older = s.connector.syncMessages(chat, before = first.last().message.id, limit = 2)
            assertTrue("There is older history", older.isNotEmpty())
            older.forEach { assertTrue(it.message.sentAt <= first.last().message.sentAt) }
            assertFalse(older.any { o -> first.any { it.message.id == o.message.id } })
            (first + older).forEach {
                assertEquals(chat, it.message.chatId)
                assertEquals(s.account.id, it.message.id.accountId)
            }
        }

    @Test
    fun sendingDeliversTheTextToTheNetwork() =
        runTest {
            val s = backgroundScope.connected()
            val chat = s.harness.chatWithHistory(s.account)
            val sent = s.sendText(chat, "Leaving at 7")
            assertEquals("Leaving at 7", sent.body)
            assertTrue(sent.isOutgoing)
            assertEquals(chat, sent.chatId)
            assertEquals(s.account.id, sent.id.accountId)
            assertTrue("Leaving at 7" in s.harness.textsSentTo(chat))
        }

    @Test
    fun incomingMessagesArriveAsEvents() =
        runTest {
            val s = backgroundScope.connected()
            val chat = s.harness.chatWithHistory(s.account)
            s.harness.receiveText(chat, "Are you still coming tonight?")
            val event =
                s.await(ConnectorEvent.NewMessage::class.java) {
                    it.message.message.body == "Are you still coming tonight?"
                }
            assertEquals(chat, event.message.message.chatId)
            assertFalse(event.message.message.isOutgoing)
            assertEquals(s.account.id, event.accountId)
        }

    @Test
    fun typingFollowsTheCapability() =
        runTest {
            val s = backgroundScope.connected()
            val chat = s.harness.chatWithHistory(s.account)
            if (s.connector.capabilities.typing) {
                s.connector.setTyping(chat, typing = true)
                s.harness.startRemoteTyping(chat)
                s.await(ConnectorEvent.Typing::class.java) { it.chatId == chat && it.typing }
            } else {
                assertUnsupported { s.connector.setTyping(chat, typing = true) }
            }
        }

    @Test
    fun reactionsFollowTheCapability() =
        runTest {
            val s = backgroundScope.connected()
            val chat = s.harness.chatWithHistory(s.account)
            val sent = s.sendText(chat, "react to me")
            when (val rule = s.connector.capabilities.reactions) {
                ReactionRule.AnyEmoji, is ReactionRule.Set -> {
                    val emoji = (rule as? ReactionRule.Set)?.allowed?.first() ?: "❤️"
                    s.connector.react(sent.id, emoji, remove = false)
                    assertEquals(listOf(emoji), s.harness.reactionsOn(sent.id))
                    s.connector.react(sent.id, emoji = null, remove = true)
                    assertEquals(emptyList<String>(), s.harness.reactionsOn(sent.id))
                }

                // Sent as a text message, the way Google Messages does on SMS (UI_DESIGN.md 5.4).
                ReactionRule.TextFallback -> {
                    s.connector.react(sent.id, "❤️", remove = false)
                    assertTrue(s.harness.textsSentTo(chat).any { "❤️" in it })
                }
            }
        }

    @Test
    fun deleteForMeAlwaysWorksAndDeleteForEveryoneFollowsTheCapability() =
        runTest {
            val s = backgroundScope.connected()
            val chat = s.harness.chatWithHistory(s.account)
            s.connector.delete(s.sendText(chat, "just for me").id, forEveryone = false)
            val second = s.sendText(chat, "for everyone")
            if (s.connector.capabilities.deleteForEveryone != null) {
                s.connector.delete(second.id, forEveryone = true)
                assertTrue(second.id in s.harness.deletedForEveryone())
            } else {
                assertUnsupported { s.connector.delete(second.id, forEveryone = true) }
            }
        }

    @Test
    fun startingAConversationFollowsTheCapability() =
        runTest {
            val s = backgroundScope.connected()
            if (s.connector.capabilities.startConversation) {
                val chat = s.connector.startConversation(s.account.id, "+15555550199")
                assertEquals(s.account.id, chat.accountId)
            } else {
                assertUnsupported { s.connector.startConversation(s.account.id, "+15555550199") }
            }
        }

    @Test
    fun disconnectingEndsTheEventStream() =
        runTest {
            val harness = newHarness()
            val done = login(harness)
            val account = harness.accountFor(done)
            val flow = harness.connector.connect(account, harness.credentialsFor(done))
            val collected = launch(Dispatchers.Default) { flow.toList() }
            harness.connector.disconnect(account.id)
            withContext(Dispatchers.Default) { withTimeout(EVENT_TIMEOUT) { collected.join() } }
        }

    private suspend fun assertUnsupported(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected UnsupportedCapabilityException")
        } catch (e: UnsupportedCapabilityException) {
            assertTrue("The reason is shown to the user, so it cannot be blank", e.reason.isNotBlank())
        }
    }

    private companion object {
        val EVENT_TIMEOUT = 10.seconds
    }
}
