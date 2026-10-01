// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.setup

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.model.NetworkId
import org.pingme.core.model.TextingMode
import org.pingme.core.store.SettingsRepository
import javax.inject.Inject

/** Where setup goes: a network's login, or the inbox when the user sets up later. */
class SetupNavigation(
    val onLogin: (NetworkId) -> Unit,
    val onDone: () -> Unit,
    val onLeave: () -> Unit,
)

/** The setup screens, one decision each (UI_DESIGN.md 3.6, BUILD_PLAN.md P2.7). */
enum class SetupPage { MODE, NOTIFICATIONS, BATTERY, CONTACTS, NETWORK }

/**
 * First-run setup: texting mode, then notifications, battery, and contacts with plain
 * reasons, then the first network's login. Setup counts as done once a login finishes or
 * the user chooses to set up later.
 */
@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        registry: ConnectorRegistry,
        private val saved: SavedStateHandle,
    ) : ViewModel() {
        /** Notifications need asking only from Android 13. */
        val pages: List<SetupPage> =
            SetupPage.entries.filter { it != SetupPage.NOTIFICATIONS || Build.VERSION.SDK_INT >= TIRAMISU }

        val page: StateFlow<Int> = saved.getStateFlow(PAGE, 0)

        val mode: StateFlow<TextingMode> =
            settings.app
                .map { it.textingMode }
                .stateIn(viewModelScope, SharingStarted.Eagerly, TextingMode.GOOGLE_MESSAGES)

        /** The networks this build can connect, in the order the design lists them. */
        val networks: List<NetworkId> = NetworkId.entries.filter { it in registry.networks }

        fun next() {
            saved[PAGE] = (page.value + 1).coerceAtMost(pages.lastIndex)
        }

        /** Back goes to the page before; false on the first, so back leaves setup. */
        fun back(): Boolean {
            if (page.value == 0) return false
            saved[PAGE] = page.value - 1
            return true
        }

        fun setMode(mode: TextingMode) {
            viewModelScope.launch { settings.updateApp { it.copy(textingMode = mode) } }
        }

        /** Setup has run; PingMe opens on the inbox from now on. */
        fun finish(then: () -> Unit) {
            viewModelScope.launch {
                settings.updateApp { it.copy(setupDone = true) }
                then()
            }
        }

        private companion object {
            const val PAGE = "page"
            const val TIRAMISU = 33
        }
    }
