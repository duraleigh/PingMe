// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.notify

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.pingme.core.service.notify.NotificationTaps
import javax.inject.Inject

/** Gives the navigation host the chat a tapped notification asked for. */
@HiltViewModel
class NotificationTapViewModel
    @Inject
    constructor(
        val taps: NotificationTaps,
    ) : ViewModel()
