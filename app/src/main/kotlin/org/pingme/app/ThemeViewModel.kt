// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.pingme.app.appearance.AppearanceRepository
import org.pingme.core.ui.theme.Appearance
import javax.inject.Inject

/** The saved appearance for the whole app. Null until it has been read, so the default never flashes. */
@HiltViewModel
class ThemeViewModel
    @Inject
    constructor(
        repository: AppearanceRepository,
    ) : ViewModel() {
        val appearance: StateFlow<Appearance?> =
            repository.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }
