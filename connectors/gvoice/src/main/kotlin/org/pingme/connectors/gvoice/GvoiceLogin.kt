// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gvoice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.pingme.connectors.gvoice.bridge.GvBridge
import org.pingme.connectors.gvoice.bridge.GvError
import org.pingme.connectors.gvoice.bridge.gvJson
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginScope
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.loginFlow

/**
 * Signing in to Google Voice (BUILD_PLAN.md Phase 6, network 4): the Google sign-in page
 * in the in-app browser, finished when voice.google.com shows the inbox; its cookies for
 * google.com are the credential, checked by asking Google Voice for the account, and
 * saved under `gvoice/<number>`.
 */
internal fun gvoiceLoginFlow(
    bridge: GvBridge,
    credentials: CredentialStore,
): LoginFlow =
    loginFlow {
        val answer = ask(LoginStep.OpenWebView("web", SIGN_IN_URL, COOKIE_DOMAINS, finishedUrlPrefix = FINISHED_PREFIX))
        if (answer !is LoginResponse.Cookies) return@loginFlow cancelled()
        val cookies = answer.cookies.filterKeys { it in WANTED }
        val missing = REQUIRED.filter { cookies[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            return@loginFlow failedWith(
                "The sign-in did not finish (no " + missing.joinToString() + " cookie). " +
                    "Sign in again and wait for the inbox.",
            )
        }
        show(LoginStep.WaitForConfirmation("check", "Checking the sign-in with Google Voice…", emoji = null))
        val json = gvJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), cookies)
        val phone =
            try {
                withContext(Dispatchers.IO) { bridge.newSession(json) { }.check() }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                return@loginFlow failedWith(reasonFor(e))
            }
        val ref = "gvoice/${phone.filter { it.isDigit() }.ifEmpty { "account" }}"
        credentials.save(ref, json.toByteArray())
        show(LoginStep.Done("done", ref, accountName = phone.ifEmpty { "Google Voice" }))
    }

private suspend fun LoginScope.failedWith(reason: String) = show(LoginStep.Failed("failed", reason, canRetry = true))

private suspend fun LoginScope.cancelled() = show(LoginStep.Failed("failed", "Sign-in cancelled", canRetry = true))

internal fun reasonFor(e: Exception): String =
    when (GvError.codeOf(e)) {
        GvError.LOGGED_OUT -> "Google did not accept the sign-in. Sign in again."
        GvError.NOT_CONNECTED -> "Could not reach Google Voice. Check the connection and try again."
        else -> "Google Voice could not sign in: ${e.message.orEmpty().substringAfter(": ")}"
    }

private const val SIGN_IN_URL = "https://voice.google.com/"
private const val FINISHED_PREFIX = "https://voice.google.com/u/"
private val COOKIE_DOMAINS = listOf("google.com", ".google.com", "voice.google.com", "accounts.google.com")
private val REQUIRED = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
private val WANTED =
    setOf(
        "SID",
        "HSID",
        "SSID",
        "APISID",
        "SAPISID",
        "NID",
        "OSID",
        "LSID",
        "SIDCC",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "__Secure-1PSIDTS",
        "__Secure-3PSIDTS",
        "__Secure-1PSIDCC",
        "__Secure-3PSIDCC",
        "__Secure-OSID",
    )
