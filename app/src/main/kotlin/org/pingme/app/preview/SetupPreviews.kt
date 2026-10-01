// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.login.LoginStepView
import org.pingme.app.setup.AskPage
import org.pingme.app.setup.ModePage
import org.pingme.app.setup.NetworkPage
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.TextKind
import org.pingme.core.model.NetworkId
import org.pingme.core.model.TextingMode
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.pingme.core.ui.R as UiR

// Previews of setup and the login steps (BUILD_PLAN.md P2.8).

@Composable
private fun Screen(content: @Composable () -> Unit) =
    PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
        Surface { Column(Modifier.padding(24.dp)) { content() } }
    }

@Preview(name = "Setup: texting mode", widthDp = 411, heightDp = 891)
@Composable
internal fun SetupModePreview() = Screen { ModePage(TextingMode.GOOGLE_MESSAGES, {}, {}) }

@Preview(name = "Setup: stay connected", widthDp = 411, heightDp = 891)
@Composable
internal fun SetupBatteryPreview() =
    Screen { AskPage(UiR.drawable.ic_sync, R.string.setup_battery_title, R.string.setup_battery_body, {}, {}) }

@Preview(name = "Setup: first network", widthDp = 411, heightDp = 891)
@Composable
internal fun SetupNetworkPreview() =
    Screen { NetworkPage(listOf(NetworkId.GMESSAGES, NetworkId.WHATSAPP, NetworkId.INSTAGRAM), {}, {}) }

@Preview(name = "Login: QR code", widthDp = 411, heightDp = 891)
@Composable
internal fun LoginQrPreview() =
    Screen {
        LoginStepView(
            LoginStep.ShowQr("qr", "pingme-preview", "Open Google Messages > Device pairing > QR scanner", true),
            {},
            {},
            {},
        )
    }

@Preview(name = "Login: matching emoji", widthDp = 411, heightDp = 891)
@Composable
internal fun LoginEmojiPreview() =
    Screen {
        LoginStepView(
            LoginStep.WaitForConfirmation("c", "Tap the matching emoji on your phone", "🦊"),
            {},
            {},
            {},
        )
    }

@Preview(name = "Login: fix it first", widthDp = 411, heightDp = 891)
@Composable
internal fun LoginFixPreview() =
    Screen {
        LoginStepView(
            LoginStep.Fix(
                "default",
                "Make Google Messages your default SMS app",
                "PingMe receives texts through Google Messages, so it has to be the phone's SMS app.",
                "Open default apps",
                "settings:android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
            ),
            {},
            {},
            {},
        )
    }

@Preview(name = "Login: code", widthDp = 411, heightDp = 891)
@Composable
internal fun LoginCodePreview() =
    Screen {
        LoginStepView(
            LoginStep.EnterText("code", "Code", TextKind.CODE, "Sent to +1 555 0100", "That code didn't work."),
            {},
            {},
            {},
        )
    }
