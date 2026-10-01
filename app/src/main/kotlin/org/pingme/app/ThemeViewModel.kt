// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.pingme.app.appearance.AppearanceRepository
import org.pingme.core.store.SettingsRepository
import org.pingme.core.ui.theme.Appearance
import javax.inject.Inject

/**
 * The saved appearance for the whole app, and whether setup has run. Each is null until
 * read, so neither the default look nor the wrong first screen ever flashes.
 */
@HiltViewModel
class ThemeViewModel
    @Inject
    constructor(
        repository: AppearanceRepository,
        settings: SettingsRepository,
    ) : ViewModel() {
        val appearance: StateFlow<Appearance?> =
            repository.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Read once: finishing setup must not swap the screens underneath the user. */
        val setupDone: StateFlow<Boolean?> =
            flow { emit(settings.app.first().setupDone) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }
