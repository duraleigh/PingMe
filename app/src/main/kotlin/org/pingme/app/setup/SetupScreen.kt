// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.R as UiR

/** First-run setup, one decision per screen (UI_DESIGN.md 3.6, BUILD_PLAN.md P2.7). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SetupRoute(
    navigation: SetupNavigation,
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val index by viewModel.page.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val pages = viewModel.pages
    BackHandler { if (!viewModel.back()) navigation.onLeave() }
    Scaffold(modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.setup_step, index + 1, pages.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearWavyProgressIndicator({ (index + 1f) / pages.size }, Modifier.fillMaxWidth())
            when (pages[index]) {
                SetupPage.MODE -> {
                    ModePage(mode, viewModel::setMode, viewModel::next)
                }

                SetupPage.NOTIFICATIONS -> {
                    NotificationsPage(viewModel::next)
                }

                SetupPage.BATTERY -> {
                    BatteryPage(viewModel::next)
                }

                SetupPage.CONTACTS -> {
                    ContactsPage(viewModel::next)
                }

                SetupPage.NETWORK -> {
                    NetworkPage(viewModel.networks, navigation.onLogin) { viewModel.finish(navigation.onDone) }
                }
            }
        }
    }
}

// Android 13 and later ask before an app may notify.
@SuppressLint("InlinedApi")
@Composable
private fun NotificationsPage(onNext: () -> Unit) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNext() }
    AskPage(
        UiR.drawable.ic_notifications,
        R.string.setup_notify_title,
        R.string.setup_notify_body,
        onAllow = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onSkip = onNext,
    )
}

// Without the exemption Android stops the connection service (DESIGN.md 6.4).
@SuppressLint("BatteryLife")
@Composable
private fun BatteryPage(onNext: () -> Unit) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onNext() }
    AskPage(
        UiR.drawable.ic_sync,
        R.string.setup_battery_title,
        R.string.setup_battery_body,
        onAllow = {
            ask.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
            )
        },
        onSkip = onNext,
        done = if (stayingConnected(context)) R.string.setup_battery_done else null,
    )
}

@Composable
private fun ContactsPage(onNext: () -> Unit) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNext() }
    AskPage(
        UiR.drawable.ic_contacts,
        R.string.setup_contacts_title,
        R.string.setup_contacts_body,
        onAllow = { ask.launch(Manifest.permission.READ_CONTACTS) },
        onSkip = onNext,
    )
}

private fun stayingConnected(context: Context) =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
