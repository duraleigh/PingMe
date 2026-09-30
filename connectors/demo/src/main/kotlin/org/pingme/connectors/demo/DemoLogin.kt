// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import kotlinx.coroutines.delay
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginScope
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.TextKind
import org.pingme.core.connector.loginFlow
import java.util.UUID

/**
 * The demo login walks through every kind of login step (BUILD_PLAN.md P1.5):
 * - Choose: QR code or account.
 * - QR path: ShowQr (the code refreshes once, as real ones do), then WaitForConfirmation.
 * - Account path: EnterText for a phone number, EnterText for a code ("000000" is wrong,
 *   to show the error), OpenWebView, then WaitForConfirmation with a matching emoji.
 * - Cancel at any point ends in Failed; success ends in Done.
 */
internal fun demoLoginFlow(
    controls: DemoControls,
    credentials: CredentialStore,
): LoginFlow =
    loginFlow {
        val finished =
            when (choose()) {
                QR -> qrPath(controls)
                ACCOUNT -> accountPath(controls)
                else -> false
            }
        if (!finished) {
            show(LoginStep.Failed("failed", "Login cancelled", canRetry = true))
            return@loginFlow
        }
        val ref = "demo/${UUID.randomUUID()}"
        credentials.save(ref, DemoConnector.DEMO_SECRET)
        show(LoginStep.Done("done", ref, accountName = "Demo"))
    }

private const val QR = "qr"
private const val ACCOUNT = "account"

private suspend fun LoginScope.choose(): String? =
    (
        ask(
            LoginStep.Choose(
                id = "method",
                title = "How do you want to connect the demo network?",
                options =
                    listOf(
                        LoginStep.Choose.Option(QR, "Scan a QR code", "Shows a code to scan on another device"),
                        LoginStep.Choose.Option(ACCOUNT, "Sign in", "Phone number, code, and a web page"),
                    ),
            ),
        ) as? LoginResponse.Choice
    )?.optionId

private suspend fun LoginScope.qrPath(controls: DemoControls): Boolean {
    val wait = controls.settings.value.confirmationDelay
    repeat(2) { refresh ->
        show(
            LoginStep.ShowQr(
                "qr",
                "pingme-demo:${UUID.randomUUID()}",
                "Scan this with any QR scanner (refresh ${refresh + 1})",
                canShare = true,
            ),
        )
        delay(wait)
    }
    show(LoginStep.WaitForConfirmation("confirm", "Scanned. Waiting for the other device to confirm…", emoji = null))
    delay(wait)
    return true
}

private suspend fun LoginScope.accountPath(controls: DemoControls): Boolean {
    ask(LoginStep.EnterText("phone", "Phone number", TextKind.PHONE_NUMBER, "Any number works", null)).text()
        ?: return false
    var error: String? = null
    while (true) {
        val code =
            ask(LoginStep.EnterText("code", "Code", TextKind.CODE, "Any code but 000000 works", error)).text()
                ?: return false
        if (code != WRONG_CODE) break
        error = "That code didn't work. Try another."
    }
    val cookies = ask(LoginStep.OpenWebView("web", "about:blank", listOf("demo.pingme.invalid")))
    if (cookies !is LoginResponse.Cookies) return false
    show(LoginStep.WaitForConfirmation("confirm", "Tap the matching emoji on your other device", emoji = "🦊"))
    delay(controls.settings.value.confirmationDelay)
    return true
}

private fun LoginResponse.text() = (this as? LoginResponse.Text)?.value

private const val WRONG_CODE = "000000"
