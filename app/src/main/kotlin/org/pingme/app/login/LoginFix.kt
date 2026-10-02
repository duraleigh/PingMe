// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.login

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.net.toUri
import org.pingme.app.R
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep

/** Something to fix on the phone first (DESIGN.md 5.4 step 1): a button to the right place, then "Check again". */
@Composable
internal fun FixIt(
    step: LoginStep.Fix,
    onRespond: (LoginResponse) -> Unit,
) {
    Stack {
        val context = LocalContext.current
        Text(step.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(step.detail, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button({ openFix(context, step.actionUri) }, Modifier.fillMaxWidth()) { Text(step.actionLabel) }
        OutlinedButton({ onRespond(LoginResponse.CheckAgain) }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.login_check_again))
        }
        TextButton({ onRespond(LoginResponse.Cancel) }) { Text(stringResource(R.string.back)) }
    }
}

private fun openFix(
    context: Context,
    uri: String,
) {
    val intent =
        when {
            uri.startsWith("package:") -> context.packageManager.getLaunchIntentForPackage(uri.removePrefix("package:"))

            uri.startsWith(
                "market:",
            ) -> Intent(Intent.ACTION_VIEW, "market://details?id=${uri.removePrefix("market:")}".toUri())

            uri.startsWith("settings:") -> Intent(uri.removePrefix("settings:"))

            else -> Intent(Intent.ACTION_VIEW, uri.toUri())
        } ?: return
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing on this phone opens it; the user can still fix it by hand and tap Check again.
    }
}
