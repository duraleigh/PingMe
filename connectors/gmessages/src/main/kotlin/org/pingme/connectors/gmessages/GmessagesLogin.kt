// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.pingme.connectors.gmessages.bridge.GmBridge
import org.pingme.connectors.gmessages.bridge.GmError
import org.pingme.connectors.gmessages.bridge.GmLoginResult
import org.pingme.connectors.gmessages.bridge.gmJson
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginScope
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.loginFlow

/**
 * Google account pairing (BUILD_PLAN.md P3.2, DESIGN.md 5.4), as login steps:
 * 1. Fix steps until Google Messages is installed and the default SMS app.
 * 2. OpenWebView: sign in to Google; the cookies for google.com come back.
 * 3. WaitForConfirmation with the emoji: the user opens Google Messages and taps the
 *    same emoji there.
 * 4. Done: the session (pairing keys and cookies) is saved under `gmessages/<phone id>`.
 *
 * There is no QR path (owner's decision, 2026-10-01). If Google refuses the sign-in
 * page, the failure says so and the owner is told in the build log.
 */
internal fun gmessagesLoginFlow(
    bridge: GmBridge,
    checks: GmessagesChecks,
    credentials: CredentialStore,
): LoginFlow =
    loginFlow {
        if (!ready(checks)) return@loginFlow cancelled()
        val cookies = ask(LoginStep.OpenWebView("web", bridge.signInUrl, COOKIE_DOMAINS, SIGNED_IN_URL))
        if (cookies !is LoginResponse.Cookies) return@loginFlow cancelled()
        val result = pair(bridge, cookies.cookies) ?: return@loginFlow
        val ref = "gmessages/${result.phoneId}"
        credentials.save(ref, result.auth.toByteArray())
        show(LoginStep.Done("done", ref, accountName = result.email))
    }

/** Keeps asking until both checks pass; false when the user gives up. */
private suspend fun LoginScope.ready(checks: GmessagesChecks): Boolean =
    fixUntil(checks::installed) {
        LoginStep.Fix(
            "install",
            "Install Google Messages",
            "PingMe receives RCS and texts through Google Messages, which is not on this phone.",
            "Get it from Google Play",
            "market:$GOOGLE_MESSAGES_PACKAGE",
        )
    } &&
        fixUntil(checks::isDefaultSmsApp) {
            LoginStep.Fix(
                "default",
                "Make Google Messages your default SMS app",
                "PingMe receives texts through Google Messages, so it has to be the phone's SMS app. " +
                    "Open default apps, choose SMS app, and pick Messages.",
                "Open default apps",
                "settings:android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
            )
        }

/** Shows [step] until [passes]; false when the user answers anything but Check again. */
private suspend fun LoginScope.fixUntil(
    passes: () -> Boolean,
    step: () -> LoginStep.Fix,
): Boolean {
    var goOn = true
    while (goOn && !passes()) goOn = ask(step()) is LoginResponse.CheckAgain
    return goOn
}

private suspend fun LoginScope.pair(
    bridge: GmBridge,
    cookies: Map<String, String>,
): GmLoginResult? {
    show(LoginStep.WaitForConfirmation("start", "Asking Google to pair…", emoji = null))
    val login =
        try {
            bridge.newLogin(gmJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), cookies))
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            return failed(e)
        }
    return try {
        val emoji = withContext(Dispatchers.IO) { login.start() }
        show(
            LoginStep.WaitForConfirmation(
                "emoji",
                "Open Google Messages on this phone and tap this emoji when it asks",
                emoji,
            ),
        )
        val json = withContext(Dispatchers.IO) { login.finish() }
        gmJson.decodeFromString(GmLoginResult.serializer(), json)
    } catch (e: CancellationException) {
        login.cancel()
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        login.cancel()
        failed(e)
    }
}

private suspend fun LoginScope.failed(e: Exception): GmLoginResult? {
    show(LoginStep.Failed("failed", reasonFor(e), canRetry = true))
    return null
}

private suspend fun LoginScope.cancelled() = show(LoginStep.Failed("failed", "Pairing cancelled", canRetry = true))

/** Plain words for each pairing failure the bridge tells apart. */
internal fun reasonFor(e: Exception): String =
    when (GmError.codeOf(e)) {
        GmError.PAIR_MISSING_COOKIES -> {
            "Google did not finish signing in. Sign in again, all the way to the Messages for web page."
        }

        GmError.PAIR_NO_DEVICES -> {
            "No phone with Google Messages is signed in to this Google account. " +
                "In Google Messages, turn on Device pairing and sign in with the same account."
        }

        GmError.PAIR_PHONE_NOT_RESPONDING -> {
            "Google Messages did not answer. Open it, check it is online, and try again."
        }

        GmError.PAIR_NO_PERMISSION -> {
            "Google refused the pairing for this account."
        }

        GmError.PAIR_WRONG_EMOJI -> {
            "The wrong emoji was tapped in Google Messages. Try again."
        }

        GmError.PAIR_CANCELLED -> {
            "The pairing was cancelled in Google Messages."
        }

        GmError.PAIR_TIMEOUT -> {
            "The emoji was not confirmed in time. Try again."
        }

        else -> {
            "Pairing failed: ${e.message?.substringAfter(": ", e.message.orEmpty())?.ifEmpty { "unknown error" }}"
        }
    }

/**
 * Sign-in is done when Google lands on the Messages for web config page, which is raw data
 * nobody should have to look at; the UI moves on by itself there.
 */
private const val SIGNED_IN_URL = "https://messages.google.com/web/config"

/** Where Google sign-in leaves the cookies the pairing needs (gobridge/gm/login.go). */
private val COOKIE_DOMAINS = listOf("google.com", "messages.google.com")
