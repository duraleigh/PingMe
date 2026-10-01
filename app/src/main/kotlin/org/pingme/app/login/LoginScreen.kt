// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.app.inbox.displayName
import org.pingme.core.connector.LoginResponse
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

/**
 * A network's login, drawn from the steps its connector sends (BUILD_PLAN.md P2.7). The
 * screen never knows which network it is; [again] titles it as logging in again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginRoute(
    again: Boolean,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colour = PingMeTheme.networkColors.accent(viewModel.network).toArgb()
    val finish by rememberUpdatedState(onFinish)
    LaunchedEffect(Unit) { if (viewModel.state.value.step == null) viewModel.begin(colour) }
    LaunchedEffect(state.finished) { if (state.finished) finish() }
    val leave = {
        viewModel.respond(LoginResponse.Cancel)
        onBack()
    }
    BackHandler(onBack = leave)
    val name = viewModel.network.displayName
    Scaffold(
        modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (again) R.string.login_again_title else R.string.login_title, name)) },
                navigationIcon = {
                    IconButton(
                        leave,
                    ) { Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoginStepView(state.step, viewModel::respond, onRetry = { viewModel.begin(colour) }, onBack = leave)
        }
    }
}
