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
        private val clock: Clock,
        private val retryDelays: RetryDelays,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val sessions = mutableMapOf<AccountId, Job>()

        // Counts network changes, so a wait that starts just after one still sees it.
        private val networkChanges = MutableStateFlow(0L)
        private var watcher: Job? = null

        /** Starts following the account list. Safe to call more than once. */
        fun start() {
            if (watcher?.isActive == true) return
            watcher = scope.launch { accounts.accounts().collect { reconcile(it) } }
        }

        fun stop() {
            watcher?.cancel()
            synchronized(sessions) {
                sessions.values.forEach { it.cancel() }
                sessions.clear()
            }
        }

        /** The phone's network changed: waiting retries go now, from the first backoff step. */
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
                    Outcome.ActionNeeded -> return
                    Outcome.Dropped -> attempt = 0
                    Outcome.Failed -> Unit
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
            return try {
                connector.connect(account, creds).collect { event ->
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
                            applier.applyChats(connector.syncChats(account.id))
                        }
                    }
                    applier.apply(event)
                    router.onEvent(event)
                    if (event is ConnectorEvent.NewMessage) keeper.arrived(event.message.message)
                }
                if (connected) Outcome.Dropped else Outcome.Failed
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
                if (connected) Outcome.Dropped else Outcome.Failed
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
        }
    }

/** Accounts the supervisor keeps connected: everything but disabled ones and ones waiting on the user. */
internal fun ConnectionState.wantsConnection() =
    this !is ConnectionState.Disabled && this !is ConnectionState.ActionNeeded
