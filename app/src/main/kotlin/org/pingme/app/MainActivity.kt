// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.pingme.core.service.ConnectionService
import org.pingme.core.store.AccountRepository
import org.pingme.core.ui.theme.PingMeTheme
import javax.inject.Inject

/**
 * Phase 0 shell: a blank screen with the app name, enough to prove signing,
 * sideloading, and CI (BUILD_PLAN.md P0.6). The inbox replaces it in P2.3.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var accounts: AccountRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { ConnectionService.startIfNeeded(this@MainActivity, accounts) }
        enableEdgeToEdge()
        setContent {
            PingMeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.displayMedium,
                        )
                    }
                }
            }
        }
    }
}
