// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import org.pingme.app.appearance.AppearanceRoute

/** Where the app can go. The inbox (P2.3) replaces [Home] as the start. */
@Serializable
object Home

@Serializable
object AppearanceStudio

@Composable
fun PingMeNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Home, modifier = modifier) {
        composable<Home> { HomePlaceholder(onAppearance = { nav.navigate(AppearanceStudio) }) }
        composable<AppearanceStudio> { AppearanceRoute(onBack = { nav.popBackStack() }) }
    }
}

/** Stands in until the inbox exists (P2.3); it only leads to the Appearance studio. */
@Composable
private fun HomePlaceholder(onAppearance: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
            Button(onClick = onAppearance) { Text(stringResource(R.string.appearance_title)) }
        }
    }
}
