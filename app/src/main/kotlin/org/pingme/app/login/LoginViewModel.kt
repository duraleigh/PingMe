// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.login

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.service.ConnectionService
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.SettingsRepository
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Clock

/** The step on screen, and whether the login has finished. */
data class LoginUiState(
    val step: LoginStep? = null,
    val finished: Boolean = false,
)

/**
 * Runs one network's login and renders it from its steps (BUILD_PLAN.md P2.7), for a new
 * account or to log an existing one in again. When it finishes the account is saved and
 * connects; during setup, setup is then done.
 */
@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val registry: ConnectorRegistry,
        private val accounts: AccountRepository,
        private val settings: SettingsRepository,
        private val clock: Clock,
        @param:ApplicationContext private val context: Context,
        saved: SavedStateHandle,
    ) : ViewModel() {
        val network: NetworkId = NetworkId.valueOf(saved.get<String>(NETWORK) ?: NetworkId.DEMO.name)
        private val again: AccountId? = saved.get<String>(ACCOUNT)?.let(::AccountId)
        private val fromSetup: Boolean = saved.get<Boolean>(FROM_SETUP) ?: false

        private val _state = MutableStateFlow(LoginUiState())
        val state: StateFlow<LoginUiState> = _state.asStateFlow()

        private var flow: LoginFlow? = null
        private var job: Job? = null

        /** Starts the login, or starts it over after a failure. [colour] is a new account's badge colour. */
        fun begin(colour: Int) {
            val connector = registry[network] ?: return
            job?.cancel()
            val login = connector.loginFlow().also { flow = it }
            _state.value = LoginUiState()
            job =
                viewModelScope.launch {
                    login.steps.collect { step ->
                        if (step is LoginStep.Done) finish(step, colour) else _state.update { it.copy(step = step) }
                    }
                }
        }

        /** Answers the step on screen. */
        fun respond(value: LoginResponse) {
            val login = flow ?: return
            val step = _state.value.step ?: return
            viewModelScope.launch { login.respond(step.id, value) }
        }

        private suspend fun finish(
            done: LoginStep.Done,
            colour: Int,
        ) {
            // Connecting starts from the first attempt; the network then reports how it went.
            val connecting = ConnectionState.Reconnecting(0, clock.now())
            val existing = again?.let { accounts.get(it) }
            accounts.upsert(
                existing?.copy(credentialRef = done.credentialRef, state = connecting)
                    ?: Account(
                        AccountId("${network.name.lowercase()}-${UUID.randomUUID()}"),
                        network,
                        done.accountName,
                        colour,
                        connecting,
                        showInInbox = true,
                        notificationMode = NotificationMode.NORMAL,
                        credentialRef = done.credentialRef,
                    ),
            )
            if (fromSetup) settings.updateApp { it.copy(setupDone = true) }
            ConnectionService.startIfNeeded(context, accounts)
            _state.update { it.copy(finished = true) }
        }

        companion object {
            const val NETWORK = "network"
            const val ACCOUNT = "account"
            const val FROM_SETUP = "fromSetup"
        }
    }
