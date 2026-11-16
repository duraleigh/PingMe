// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.Credentials
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.SendResult
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * A send whose answer from the phone is lost may still be sent by the phone a moment later
 * (owner, 2026-11-16: the same message once as sent and once as "Not sent").
 */
class LateSendTest {
    private val bridge = FakeGmBridge()
    private val connector =
        GmessagesConnector(
            bridge,
            AllGood,
            MemoryCredentialStore(),
            Files.createTempDirectory("gmessages").toFile(),
            Clock.System,
        )
    private val account =
        Account(
            id = AccountId("gm-late"),
            network = NetworkId.GMESSAGES,
            displayName = "Phone",
            colorArgb = 0,
            state = ConnectionState.Connected,
            showInInbox = true,
            notificationMode = NotificationMode.NORMAL,
            credentialRef = "ref",
        )
    private val chat = account.id.chat("12")
    private val pending = account.id.message("pending-${UUID.randomUUID()}")
    private val draft = OutgoingMessage(pending, "Late hello", emptyList(), null, null, forceSms = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<ConnectorEvent>()

    @After
    fun tearDown() = scope.cancel()

    /** Opens a connection and waits until the phone is ready on it. */
    private fun connect(): Job =
        runBlocking {
            val flow = connector.connect(account, Credentials("ref", FakeGmBridge.AUTH.encodeToByteArray()))
            val seen = events.size
            val job = scope.launch { flow.collect { events += it } }
            waitFor { events.drop(seen).any { it is ConnectorEvent.State && it.state == ConnectionState.Connected } }
            job
        }

    private fun waitFor(condition: () -> Boolean) =
        runBlocking {
            withTimeout(WAIT) {
                while (!condition()) kotlinx.coroutines.delay(STEP_MS)
            }
        }

    private fun send(): SendResult = runBlocking { connector.send(chat, draft) {} }

    private fun theCopyTookThePendingPlace() =
        events.any { it is ConnectorEvent.MessageRemoved && it.messageId == pending } &&
            events.any { it is ConnectorEvent.NewMessage && it.message.message.body == "Late hello" }

    @Test
    fun aSendThePhoneSendsAfterAllTakesTheNotSentBubblesPlace() {
        connect()
        bridge.phone.answersLost = true
        val failed = send()
        assertTrue(failed is SendResult.Failed && failed.retryable)
        bridge.phone.sendUnanswered()
        waitFor(::theCopyTookThePendingPlace)
    }

    @Test
    fun theCopyIsStillMatchedOnTheNextConnection() {
        val first = connect()
        bridge.phone.answersLost = true
        assertTrue(send() is SendResult.Failed)
        // A network change: the connection closes and a new one opens.
        runBlocking {
            connector.disconnect(account.id)
            first.join()
        }
        connect()
        bridge.phone.sendUnanswered()
        waitFor(::theCopyTookThePendingPlace)
    }

    @Test
    fun tryAgainDoesNotSendWhatThePhoneAlreadySent() {
        connect()
        bridge.phone.answersLost = true
        assertTrue(send() is SendResult.Failed)
        // The phone sent it, but word of that never reached PingMe.
        bridge.phone.sendUnanswered(quietly = true)
        bridge.phone.answersLost = false
        val again = send()
        assertTrue(again is SendResult.Sent && again.message.message.body == "Late hello")
        assertEquals(1, bridge.phone.sendRequests.get())
        assertEquals(listOf("Late hello"), bridge.phone.sentTexts["12"].orEmpty())
    }

    @Test
    fun aSendBeforeThePhoneIsReadyIsHeldNotHandedOver() {
        val result = send()
        assertTrue(result is SendResult.Failed && result.notConnected)
        assertEquals(0, bridge.phone.sendRequests.get())
    }

    private companion object {
        val WAIT = 10.seconds
        const val STEP_MS = 20L
    }
}
