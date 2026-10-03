// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.pingme.core.connector.ActionNeededException
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceId
import org.pingme.core.model.SpaceKind
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class ConnectorSupervisorTest : ServiceTest() {
    private val connector = FakeConnector()
    private val credentials = MemoryCredentialStore()
    private var delays: (Int) -> Duration = { 20.milliseconds }
    private lateinit var supervisor: ConnectorSupervisor

    @Before
    fun build() {
        supervisor =
            ConnectorSupervisor(
                registry = ConnectorRegistry(mapOf(NetworkId.DEMO to connector)),
                accounts = accounts,
                credentials = credentials,
                applier = applier,
                router = router,
                keeper = keeper,
                history = history,
                clock = clock,
                retryDelays = { delays(it) },
                housekeeping = StoreHousekeeping(messages, clock),
                previews = QuietPreviews(context),
                tapbacks = Tapbacks(messages),
                scope = scope,
            )
        runBlocking { credentials.save("cred", byteArrayOf(1)) }
    }

    private suspend fun state() = accounts.get(accountId)?.state

    @Test
    fun connectsSyncsChatsAndStoresWhatArrives() =
        runBlocking {
            connector.chats = listOf(chatSnapshot())
            connector.sessions +=
                {
                    emit(ConnectorEvent.State(accountId, ConnectionState.Connected))
                    emit(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1", body = "hello")))
                    awaitCancellation()
                }
            accounts.upsert(account(ConnectionState.Reconnecting(0, now)))
            supervisor.start()
            eventually { messages.get(accountId.message("m1")) != null }
            assertEquals(ConnectionState.Connected, state())
            assertEquals("Sam Ortiz", chats.get(accountId.chat("c1"))?.title)
        }

    @Test
    fun anEventTheStoreRefusesIsSkippedAndTheConnectionStays() =
        runBlocking {
            connector.chats = listOf(chatSnapshot())
            // A space for an account that does not exist: the database refuses the row.
            val stray = Space(SpaceId("s"), AccountId("ghost"), "Stray", SpaceKind.WHATSAPP_COMMUNITY, emptyList())
            connector.sessions +=
                {
                    emit(ConnectorEvent.State(accountId, ConnectionState.Connected))
                    emit(ConnectorEvent.SpaceUpdated(accountId, stray))
                    emit(ConnectorEvent.NewMessage(accountId, messageSnapshot("after", body = "still here")))
                    awaitCancellation()
                }
            accounts.upsert(account(ConnectionState.Reconnecting(0, now)))
            supervisor.start()
            eventually { messages.get(accountId.message("after")) != null }
            assertEquals(ConnectionState.Connected, state())
        }

    @Test
    fun chatsWithNothingStoredFetchTheirHistory() =
        runBlocking {
            connector.chats = listOf(chatSnapshot("c1"), chatSnapshot("c2"))
            accounts.upsert(account(ConnectionState.Reconnecting(0, now)))
            applier.apply(ConnectorEvent.NewMessage(accountId, messageSnapshot("m0", chatRemote = "c2")))
            connector.sessions +=
                {
                    emit(ConnectorEvent.State(accountId, ConnectionState.Connected))
                    awaitCancellation()
                }
            supervisor.start()
            eventually { history.backfilled.isNotEmpty() }
            assertEquals("only the chat with nothing stored", listOf(accountId.chat("c1")), history.backfilled)
        }

    @Test
    fun transientFailuresRetryWithGrowingWaits() =
        runBlocking {
            val attempts = mutableListOf<Int>()
            delays = {
                attempts += it
                20.milliseconds
            }
            connector.sessions += { throw IOException("socket closed") }
            accounts.upsert(account())
            supervisor.start()
            eventually { connector.connects.get() >= 4 }
            assertEquals(listOf(1, 2, 3), attempts.take(3))
            assertTrue(state() is ConnectionState.Reconnecting)
        }

    @Test
    fun aDroppedConnectionStartsTheBackoffOver() =
        runBlocking {
            val attempts = mutableListOf<Int>()
            delays = {
                attempts += it
                20.milliseconds
            }
            connector.sessions +=
                {
                    emit(ConnectorEvent.State(accountId, ConnectionState.Connected))
                    delay(10)
                }
            accounts.upsert(account())
            supervisor.start()
            eventually { connector.connects.get() >= 3 }
            // Each session connected and then ended, so every retry is a first attempt.
            assertEquals(listOf(1, 1), attempts.take(2))
        }

    @Test
    fun aNetworkChangeCutsTheWaitShort() =
        runBlocking {
            delays = { 10.minutes }
            connector.sessions += { throw IOException("offline") }
            accounts.upsert(account())
            supervisor.start()
            eventually { state() is ConnectionState.Reconnecting }
            assertEquals(1, connector.connects.get())
            supervisor.onNetworkChanged()
            eventually { connector.connects.get() == 2 }
        }

    @Test
    fun aNetworkChangeWhileConnectedOpensTheConnectionAgainAtOnce() =
        runBlocking {
            // The old connection may be dead without saying so (owner, Gate G3).
            delays = { 10.minutes }
            connector.sessions +=
                {
                    emit(ConnectorEvent.State(accountId, ConnectionState.Connected))
                    connector.closed.first()
                }
            accounts.upsert(account())
            supervisor.start()
            eventually { state() == ConnectionState.Connected && connector.connects.get() == 1 }
            supervisor.onNetworkChanged()
            eventually { connector.connects.get() == 2 }
        }

    @Test
    fun aNetworkChangeJustBeforeTheWaitIsNotMissed() =
        runBlocking {
            // The network changes after the session ended but before the wait begins.
            delays = {
                supervisor.onNetworkChanged()
                10.minutes
            }
            connector.sessions += { throw IOException("offline") }
            accounts.upsert(account())
            supervisor.start()
            eventually { connector.connects.get() >= 2 }
        }

    @Test
    fun whenTheUserMustActItStopsAndSaysSo() =
        runBlocking {
            connector.sessions +=
                {
                    throw ActionNeededException(
                        "Pair again in Google Messages",
                        "package:com.google.android.apps.messaging",
                    )
                }
            accounts.upsert(account())
            supervisor.start()
            eventually { state() is ConnectionState.ActionNeeded }
            delay(100)
            assertEquals(1, connector.connects.get())
            assertEquals(
                ConnectionState.ActionNeeded(
                    "Pair again in Google Messages",
                    "package:com.google.android.apps.messaging",
                ),
                state(),
            )
        }

    @Test
    fun anActionNeededStateFromTheNetworkAlsoStops() =
        runBlocking {
            connector.sessions += {
                emit(ConnectorEvent.State(accountId, ConnectionState.ActionNeeded("Unpaired", null)))
                awaitCancellation()
            }
            accounts.upsert(account())
            supervisor.start()
            eventually { state() is ConnectionState.ActionNeeded }
            delay(100)
            assertEquals(1, connector.connects.get())
        }

    @Test
    fun missingCredentialsAskTheUserToSignIn() =
        runBlocking {
            accounts.upsert(account(credentialRef = "missing"))
            supervisor.start()
            eventually { state() is ConnectionState.ActionNeeded }
            assertEquals(0, connector.connects.get())
        }

    @Test
    fun disabledAccountsAreNotConnectedAndDisablingDisconnects() =
        runBlocking {
            connector.sessions += {
                emit(ConnectorEvent.State(accountId, ConnectionState.Connected))
                awaitCancellation()
            }
            accounts.upsert(account(ConnectionState.Disabled))
            supervisor.start()
            delay(100)
            assertEquals(0, connector.connects.get())

            accounts.upsert(account(ConnectionState.Reconnecting(0, now)))
            eventually { state() == ConnectionState.Connected }
            accounts.updateState(accountId, ConnectionState.Disabled)
            delay(100)
            // Nothing reconnects a disabled account.
            assertEquals(1, connector.connects.get())
            assertEquals(ConnectionState.Disabled, state())
        }
}
