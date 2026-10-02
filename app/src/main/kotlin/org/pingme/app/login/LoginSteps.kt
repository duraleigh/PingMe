// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.login

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.pingme.app.R
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.TextKind

/** Draws one login step and sends back the answer (UI_DESIGN.md 3.6). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LoginStepView(
    step: LoginStep?,
    onRespond: (LoginResponse) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Stack {
        when (step) {
            null, is LoginStep.Done -> {
                Waiting(stringResource(R.string.login_starting), null)
            }

            is LoginStep.Choose -> {
                Choices(step, onRespond)
            }

            is LoginStep.ShowQr -> {
                Qr(step, onRespond)
            }

            is LoginStep.EnterText -> {
                TextAnswer(step, onRespond)
            }

            is LoginStep.OpenWebView -> {
                WebSignIn(step, onRespond)
            }

            is LoginStep.Fix -> {
                FixIt(step, onRespond)
            }

            is LoginStep.WaitForConfirmation -> {
                Waiting(step.hint, step.emoji)
            }

            is LoginStep.Failed -> {
                Text(step.reason, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (step.canRetry) Button(onRetry) { Text(stringResource(R.string.login_try_again)) }
                TextButton(onBack) { Text(stringResource(R.string.back)) }
            }
        }
    }
}

// A shape-morphing indicator while the other side confirms; the emoji-match step shows the emoji at display size.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Waiting(
    hint: String,
    emoji: String?,
) {
    Stack {
        if (emoji != null) Text(emoji, style = MaterialTheme.typography.displayLarge)
        LoadingIndicator(Modifier.size(72.dp))
        Text(hint, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Choices(
    step: LoginStep.Choose,
    onRespond: (LoginResponse) -> Unit,
) {
    Stack {
        Text(step.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth()) {
            step.options.forEach { option ->
                ListItem(
                    headlineContent = { Text(option.label) },
                    supportingContent = option.description?.let { { Text(it) } },
                    modifier =
                        Modifier.selectable(false, role = Role.Button) { onRespond(LoginResponse.Choice(option.id)) },
                )
            }
        }
    }
}

// The QR to scan; on a single phone it can go to another screen through the share sheet.
@Composable
private fun Qr(
    step: LoginStep.ShowQr,
    onRespond: (LoginResponse) -> Unit,
) {
    Stack {
        val context = LocalContext.current
        QrCode(step.qrData, stringResource(R.string.login_qr))
        Text(step.hint, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (step.canShare) {
            OutlinedButton({
                onRespond(LoginResponse.ShareRequested)
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, step.qrData)
                context.startActivity(Intent.createChooser(share, null))
            }) { Text(stringResource(R.string.login_qr_share)) }
        }
    }
}

@Composable
private fun TextAnswer(
    step: LoginStep.EnterText,
    onRespond: (LoginResponse) -> Unit,
) {
    Stack {
        var text by rememberSaveable(step.id, step.error) { mutableStateOf("") }
        val secret = step.kind == TextKind.PASSWORD || step.kind == TextKind.TOKEN
        OutlinedTextField(
            text,
            { text = it },
            Modifier.fillMaxWidth(),
            label = { Text(step.label) },
            supportingText = (step.error ?: step.hint)?.let { { Text(it) } },
            isError = step.error != null,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardFor(step.kind)),
        )
        Button({ onRespond(LoginResponse.Text(text.trim())) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank()) {
            Text(stringResource(R.string.login_continue))
        }
    }
}

private fun keyboardFor(kind: TextKind) =
    when (kind) {
        TextKind.PHONE_NUMBER -> KeyboardType.Phone
        TextKind.CODE -> KeyboardType.NumberPassword
        TextKind.PASSWORD -> KeyboardType.Password
        TextKind.TOKEN, TextKind.TEXT -> KeyboardType.Text
    }

// The network's own sign-in page; its cookies for the named domains go back to the connector and stay on the phone.
@Composable
private fun WebSignIn(
    step: LoginStep.OpenWebView,
    onRespond: (LoginResponse) -> Unit,
) {
    Stack {
        val done = step.finishedUrlPrefix
        var finished by remember(step.id) { mutableStateOf(false) }
        val finish = {
            if (!finished) {
                finished = true
                onRespond(LoginResponse.Cookies(cookiesFor(step.cookieDomains)))
            }
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(
                                view: WebView?,
                                url: String?,
                            ) {
                                if (done != null && url != null && url.startsWith(done)) finish()
                            }
                        }
                    @Suppress("SetJavaScriptEnabled") // Sign-in pages need it.
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google refuses sign-in from pages that announce themselves as a WebView
                    // ("this browser may not be secure"); the plain Chrome-on-Android string passes.
                    settings.userAgentString = browserUserAgent(settings.userAgentString)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(step.url)
                }
            },
            modifier = Modifier.fillMaxWidth().height(WEB_HEIGHT),
        )
        Button(finish, Modifier.fillMaxWidth()) { Text(stringResource(R.string.login_web_done)) }
    }
}

/** The WebView's own user agent without the parts that mark it as a WebView. */
internal fun browserUserAgent(webViewAgent: String): String =
    webViewAgent.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")

private fun cookiesFor(domains: List<String>): Map<String, String> {
    val jar = CookieManager.getInstance()
    return domains
        .flatMap { domain -> jar.getCookie("https://$domain").orEmpty().split(";") }
        .mapNotNull { pair -> pair.trim().split("=", limit = 2).takeIf { it.size == 2 } }
        .associate { (name, value) -> name to value }
}

// Every step's parts, stacked and centred.
@Composable
internal fun Stack(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp), Alignment.CenterHorizontally, content)
}

private val WEB_HEIGHT = 480.dp
