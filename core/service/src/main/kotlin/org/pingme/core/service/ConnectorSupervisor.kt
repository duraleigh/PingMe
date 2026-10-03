// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.pingme.core.connector.ActionNeededException
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.Credentials
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.store.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * Keeps every account connected (DESIGN.md 5.3, 6.4; BUILD_PLAN.md P1.4). Per account, one
 * coroutine connects, writes events into the store, and on failure retries with [Backoff]
 * (through [RetryDelays]),
 * resetting when the network changes. A failure that needs the user ([ActionNeededException]
 * or a State(ActionNeeded) event) stops the retries and is shown instead. Disabled accounts
 * are not connected.
 */
@Singleton
class ConnectorSupervisor
    @Inject
    constructor(
        private val registry: ConnectorRegistry,
        private val accounts: AccountRepository,
        private val credentials: CredentialStore,
        private val applier: EventApplier,
        private val router: NotificationRouter,
        private val keeper: MediaKeeper,
        private val history: HistorySync,
        private val clock: Clock,
        private val retryDelays: RetryDelays,
        private val housekeeping: StoreHousekeeping,
        private val previews: org.pingme.core.service.links.PreviewRequests,
        private val tapbacks: Tapbacks,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val sessions = mutableMapOf<AccountId, Job>()

        // Counts network changes, so a wait that starts just after one still sees it.
        private val networkChanges = MutableStateFlow(0L)
        private var watcher: Job? = null

        /** Starts following the account list. Safe to call more than once. */
        fun start() {
            if (watcher?.isActive == true) return
            watcher =
                scope.launch {
                    housekeeping.run()
                    accounts.accounts().collect { reconcile(it) }
                }
        }

        fun stop() {
            watcher?.cancel()
            synchronized(sessions) {
                sessions.values.forEach { it.cancel() }
                sessions.clear()
            }
        }

        /**
         * The phone's network changed: waiting retries go now, from the first backoff step,
         * and a live connection is dropped and opened again at once, since the old one may
         * be dead without saying so (owner, Gate G3: stale connections after a Wi-Fi change).
         */
        fun onNetworkChanged() {
            networkChanges.update { it + 1 }
        }

        private fun reconcile(all: List<Account>) =
            synchronized(sessions) {
                val wanted = all.filter { it.state.wantsConnection() }.associateBy { it.id }
                (sessions.keys - wanted.keys).forEach { sessions.remove(it)?.cancel() }
                sessions.entries.removeAll { (_, job) -> !job.isActive }
                wanted.values
                    .filter { it.id !in sessions }
                    .forEach { account -> sessions[account.id] = scope.launch { supervise(account) } }
            }

        private suspend fun supervise(account: Account) {
            val connector = registry[account.network] ?: return
            var attempt = 0
            while (true) {
                val outcome = runSession(account, connector)
                // Noted as soon as the session ends, so a network change from then on is never missed.
                val seen = networkChanges.value
                when (outcome) {
                    Outcome.ActionNeeded -> {
                        return
                    }

                    Outcome.Dropped -> {
                        attempt = 0
                    }

                    Outcome.Failed -> {
                        Unit
                    }

                    Outcome.NetworkChanged -> {
                        attempt = 0
                        continue
                    }
                }
                attempt++
                val wait = retryDelays.delayFor(attempt)
                accounts.updateState(account.id, ConnectionState.Reconnecting(attempt, clock.now() + wait))
                // A network change cuts the wait short and starts the backoff over.
                val networkCame = withTimeoutOrNull(wait) { networkChanges.first { it != seen } } != null
                if (networkCame) attempt = 0
            }
        }

        /** One connection, start to end. */
        private suspend fun runSession(
            account: Account,
            connector: org.pingme.core.connector.Connector,
        ): Outcome {
            val creds = loadCredentials(account) ?: return actionNeeded(account.id, "Sign in again", null)
            var connected = false
            var networkChanged = false
            val networkAtStart = networkChanges.value
            return try {
                kotlinx.coroutines.coroutineScope {
                    // A network change while connected closes the session; it is opened again at once.
                    val watcher =
                        launch {
                            networkChanges.first { it != networkAtStart }
                            networkChanged = true
                            connector.disconnect(account.id)
                        }
                    try {
                        collectSession(account, connector, creds) { connected = true }
                    } finally {
                        watcher.cancel()
                    }
                }
                when {
                    networkChanged && connected -> Outcome.NetworkChanged
                    connected -> Outcome.Dropped
                    else -> Outcome.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActionNeededException) {
                actionNeeded(account.id, e.reason, e.deepLink)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Any other failure is treated as transient: network blips, a restarted
                // Google Messages, a socket closed while the phone slept. Logged on the
                // phone only (no telemetry, DESIGN.md 6.5).
                Log.w(TAG, "${account.network} connection ended; retrying", e)
                when {
                    networkChanged && connected -> Outcome.NetworkChanged
                    connected -> Outcome.Dropped
                    else -> Outcome.Failed
                }
            }
        }

        /** Every event of one connection into the store, until the connection ends. */
        private suspend fun collectSession(
            account: Account,
            connector: org.pingme.core.connector.Connector,
            creds: Credentials,
            onConnected: () -> Unit,
        ) {
            var connected = false
            connector.connect(account, creds).collect { raw ->
                // An iPhone reaction sent as text lands on the message it means, not as a bubble.
                val event = tapbacks.rewrite(raw)
                if (event is ConnectorEvent.State) {
                    val state = event.state
                    if (state is ConnectionState.ActionNeeded) {
                        throw ActionNeededException(
                            state.reason,
                            state.deepLink,
                        )
                    }
                    if (state == ConnectionState.Connected && !connected) {
                        connected = true
                        onConnected()
                        val chats = connector.syncChats(account.id)
                        applier.applyChats(chats)
                        history.chatsArrived(chats)
                        runCatching { connector.refreshPeople(account.id) }
                            .onFailure { Log.w(TAG, "${account.network}: could not refresh its people", it) }
                    }
                }
                // Fresh is decided before the store has the message; the notification goes after.
                val fresh = router.isFresh(event)
                try {
                    applier.apply(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // One event the store refuses must not end the connection: it would come
                    // again on every retry and the account would never connect (owner, Gate G7).
                    Log.w(TAG, "${account.network}: could not apply ${event::class.simpleName}; skipped", e)
                    return@collect
                }
                router.onEvent(event, fresh)
                // Media downloads when the message comes and again when an update brings the
                // file the phone has now finished fetching itself (owner, Gate G3).
                when (event) {
                    is ConnectorEvent.NewMessage -> {
                        keeper.arrived(event.message.message)
                        if (fresh) previews.request(event.message.message)
                    }

                    is ConnectorEvent.MessageUpdated -> {
                        keeper.arrived(event.message.message)
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

        private suspend fun loadCredentials(account: Account): Credentials? =
            credentials.load(account.credentialRef)?.let { Credentials(account.credentialRef, it) }

        private suspend fun actionNeeded(
            accountId: AccountId,
            reason: String,
            deepLink: String?,
        ): Outcome {
            accounts.updateState(accountId, ConnectionState.ActionNeeded(reason, deepLink))
            return Outcome.ActionNeeded
        }

        private companion object {
            const val TAG = "PingMeSupervisor"
        }

        private enum class Outcome {
            /** Was connected, then the connection ended: retry from the first step. */
            Dropped,

            /** Never got connected: keep backing off. */
            Failed,

            /** The user must act; stop until they do. */
            ActionNeeded,

            /** Was connected; dropped on purpose because the network changed: go again now. */
            NetworkChanged,
        }
    }

/** Accounts the supervisor keeps connected: everything but disabled ones and ones waiting on the user. */
internal fun ConnectionState.wantsConnection() =
    this !is ConnectionState.Disabled && this !is ConnectionState.ActionNeeded
