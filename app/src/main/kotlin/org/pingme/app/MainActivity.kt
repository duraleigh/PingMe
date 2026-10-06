// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.pingme.app.chat.voice.HardwareKeys
import org.pingme.core.service.ConnectionService
import org.pingme.core.service.notify.NotificationTaps
import org.pingme.core.store.AccountRepository
import org.pingme.core.ui.theme.PingMeTheme
import javax.inject.Inject

/** The one activity. Applies the saved appearance and hosts every screen. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var accounts: AccountRepository

    @Inject lateinit var taps: NotificationTaps

    @Inject lateinit var shares: org.pingme.app.share.ShareRequests

    private val theme: ThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { ConnectionService.startIfNeeded(this@MainActivity, accounts) }
        // A tapped notification names its chat; the navigation host opens it (owner, Gate G2).
        taps.fromIntent(intent)
        // Text or files shared from another app open the picker (owner, Gate G3).
        shares.fromIntent(intent)
        enableEdgeToEdge()
        setContent {
            val appearance by theme.appearance.collectAsStateWithLifecycle()
            val setupDone by theme.setupDone.collectAsStateWithLifecycle()
            // Read from disk in a few milliseconds; drawing nothing until then avoids a flash of the default look.
            val look = appearance
            val done = setupDone
            if (look != null && done != null) PingMeTheme(look) { PingMeNavHost(startAtSetup = !done) }
        }
    }

    /** The side key records a voice note in the open chat (owner, 2026-10-06); see HardwareKeys. */
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = HardwareKeys.onKeyDown(keyCode, event.repeatCount) || super.onKeyDown(keyCode, event)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Opened again while already in front: the side key's "open PingMe" (see HardwareKeys).
        if (HardwareKeys.fromIntent(intent, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) return
        taps.fromIntent(intent)
        shares.fromIntent(intent)
    }
}
