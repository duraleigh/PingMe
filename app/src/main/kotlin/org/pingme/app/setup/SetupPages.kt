// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.setup

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.inbox.NetworkBadge
import org.pingme.app.inbox.displayName
import org.pingme.core.model.NetworkId
import org.pingme.core.model.TextingMode

/** Google Messages mode or native SMS mode (DESIGN.md 5.2). */
@Composable
internal fun ModePage(
    mode: TextingMode,
    onPick: (TextingMode) -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.setup_mode_title), style = MaterialTheme.typography.headlineMedium)
        ModeCard(
            TextingMode.GOOGLE_MESSAGES,
            mode,
            R.string.setup_mode_gm,
            R.string.setup_mode_gm_body,
            stringResource(R.string.setup_mode_gm_badge),
            onPick,
        )
        ModeCard(TextingMode.NATIVE_SMS, mode, R.string.setup_mode_sms, R.string.setup_mode_sms_body, null, onPick)
        Text(stringResource(R.string.setup_mode_later), style = MaterialTheme.typography.bodyMedium)
        Button(onNext, Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_next)) }
    }
}

@Composable
private fun ModeCard(
    option: TextingMode,
    mode: TextingMode,
    @StringRes title: Int,
    @StringRes body: Int,
    badge: String?,
    onPick: (TextingMode) -> Unit,
) {
    val picked = option == mode
    OutlinedCard(
        Modifier.fillMaxWidth().selectable(picked, role = Role.RadioButton) { onPick(option) },
        border =
            BorderStroke(
                if (picked) 2.dp else 1.dp,
                if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            RadioButton(picked, onClick = null)
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                badge?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** One permission or exemption: why, then Allow or Not now. [done] replaces the ask when it is already granted. */
@Composable
internal fun AskPage(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes body: Int,
    onAllow: () -> Unit,
    onSkip: () -> Unit,
    @StringRes done: Int? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(icon), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
        if (done != null) {
            Text(stringResource(done), style = MaterialTheme.typography.bodyLarge)
            Button(onSkip, Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_next)) }
        } else {
            Button(onAllow, Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_allow)) }
            TextButton(onSkip, Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_not_now)) }
        }
    }
}

/** The first network to connect, each with its risk in plain words (DESIGN.md 7). */
@Composable
internal fun NetworkPage(
    networks: List<NetworkId>,
    onPick: (NetworkId) -> Unit,
    onLater: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.setup_network_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.setup_network_body), style = MaterialTheme.typography.bodyLarge)
        if (networks.isEmpty()) Text(stringResource(R.string.setup_network_none))
        NetworkChoices(networks, onPick)
        TextButton(onLater, Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_network_later)) }
    }
}

/** Each network this build can connect, with its risk; tapping one starts its login. */
@Composable
fun NetworkChoices(
    networks: List<NetworkId>,
    onPick: (NetworkId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        networks.forEach { network ->
            ListItem(
                headlineContent = { Text(network.displayName) },
                supportingContent = { Text(stringResource(riskOf(network))) },
                leadingContent = { NetworkBadge(network, null) },
                modifier = Modifier.selectable(false, role = Role.Button) { onPick(network) },
            )
        }
    }
}

/** The owner's stance on each network, in plain words (DESIGN.md 7). */
@StringRes
fun riskOf(network: NetworkId) =
    when (network) {
        NetworkId.SMS, NetworkId.TELEGRAM -> R.string.risk_official
        NetworkId.GMESSAGES, NetworkId.SIGNAL, NetworkId.GVOICE -> R.string.risk_tolerated
        NetworkId.WHATSAPP -> R.string.risk_against_terms
        NetworkId.INSTAGRAM, NetworkId.MESSENGER -> R.string.risk_ban
        NetworkId.FBPAGE -> R.string.risk_page
        NetworkId.DEMO -> R.string.risk_demo
    }
