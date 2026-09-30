// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.pingme.core.service.ConnectionService
import org.pingme.core.store.AccountRepository
import org.pingme.core.ui.theme.PingMeTheme
import javax.inject.Inject

/** The one activity. Applies the saved appearance and hosts every screen. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var accounts: AccountRepository

    private val theme: ThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { ConnectionService.startIfNeeded(this@MainActivity, accounts) }
        enableEdgeToEdge()
        setContent {
            val appearance by theme.appearance.collectAsStateWithLifecycle()
            // Read from disk in a few milliseconds; drawing nothing until then avoids a flash of the default look.
            appearance?.let { PingMeTheme(it) { PingMeNavHost() } }
        }
    }
}
