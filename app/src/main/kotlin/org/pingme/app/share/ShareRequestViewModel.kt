// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.share

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Gives the navigation host the share another app handed PingMe. */
@HiltViewModel
class ShareRequestViewModel
    @Inject
    constructor(
        val shares: ShareRequests,
    ) : ViewModel()
