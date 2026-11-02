// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pingme.core.connector.Diag
import javax.inject.Inject

/**
 * Brings the connections back without the owner opening the app: after the phone restarts
 * and after PingMe is updated. Until this, every install killed the connection service and
 * nothing live arrived until the next time PingMe was opened (owner, 2026-10-07: a message
 * sent from the Instagram app never showed; the service had been down since the 10:41 PM
 * install). Both broadcasts are among those Android lets a foreground service start from.
 */
@AndroidEntryPoint
class ConnectionReceiver : BroadcastReceiver() {
    @Inject lateinit var keeper: ConnectionKeeper

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        val known = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!known && action != ACTION_WATCH) return
        if (known) Diag.connection(TAG, "$action: starting the connection service if an account needs it")
        val pending = goAsync()
        scope.launch {
            try {
                keeper.checkNow(action.orEmpty())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PingMeConnection"

        /** The keeper's alarm (owner, 2026-11-01). */
        const val ACTION_WATCH = "org.pingme.action.CONNECTION_WATCH"
    }
}
