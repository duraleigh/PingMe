// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.whatsapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.pingme.connectors.whatsapp.bridge.WaBridge
import org.pingme.connectors.whatsapp.bridge.WaEvent
import org.pingme.connectors.whatsapp.bridge.WaSession
import org.pingme.connectors.whatsapp.bridge.waJson
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginScope
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.TextKind
import org.pingme.core.connector.loginFlow
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Linking WhatsApp by phone number (BUILD_PLAN.md Phase 6, network 1; owner, 2026-10-02:
 * the eight-character code is the one way, there is no QR path):
 * 1. EnterText: the WhatsApp number, with its country code.
 * 2. WaitForConfirmation with the code, shown large: in WhatsApp on the phone, Linked
 *    devices > Link a device > Link with phone number instead, then type it.
 * 3. Done once WhatsApp confirms: the device keys are in `whatsapp/<digits>.db`, and the
 *    credential ref `whatsapp/<digits>` names them.
 */
internal fun whatsappLoginFlow(
    bridge: WaBridge,
    credentials: CredentialStore,
    dir: File,
): LoginFlow =
    loginFlow {
        var error: String? = null
        while (true) {
            val answer =
                ask(
                    LoginStep.EnterText(
                        "phone",
                        "Your WhatsApp phone number, with the country code",
                        TextKind.PHONE_NUMBER,
                        "+1 919 555 0123",
                        error,
                    ),
                )
            if (answer !is LoginResponse.Text) return@loginFlow cancelled()
            val digits = answer.value.filter { it.isDigit() }
            if (digits.length < MIN_DIGITS || digits.startsWith("0")) {
                error = "That is not a full number with a country code"
                continue
            }
            link(bridge, credentials, dir, digits)
            return@loginFlow
        }
    }

private suspend fun LoginScope.link(
    bridge: WaBridge,
    credentials: CredentialStore,
    dir: File,
    digits: String,
) {
    show(LoginStep.WaitForConfirmation("start", "Asking WhatsApp for a code…", emoji = null))
    val linked = CompletableDeferred<WaEvent>()
    val session: WaSession =
        try {
            bridge.newSession(WhatsappConnector.storePath(dir, "whatsapp/$digits")) { json ->
                when (val event = runCatching { waJson.decodeFromString(WaEvent.serializer(), json) }.getOrNull()) {
                    is WaEvent.PairSuccess, is WaEvent.PairError, is WaEvent.LoggedOut -> linked.complete(event)
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            return failed(e)
        }
    try {
        val problem = if (withContext(Dispatchers.IO) { session.isLoggedIn() }) null else pair(session, digits, linked)
        if (problem != null) {
            failedWith(problem)
        } else {
            val ref = "whatsapp/$digits"
            credentials.save(ref, digits.toByteArray())
            show(LoginStep.Done("done", ref, accountName = "+$digits"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        failed(e)
    } finally {
        // The connector opens its own session once the account is saved.
        withContext(Dispatchers.IO) { runCatching { session.close() } }
    }
}

/** Shows the code and waits for WhatsApp to confirm it; the reason when it does not. */
private suspend fun LoginScope.pair(
    session: WaSession,
    digits: String,
    linked: CompletableDeferred<WaEvent>,
): String? {
    val code = withContext(Dispatchers.IO) { session.pairCode(digits) }
    show(
        LoginStep.WaitForConfirmation(
            "code",
            "On your phone, open WhatsApp > Linked devices > Link a device > " +
                "Link with phone number instead, and type this code",
            code,
        ),
    )
    return when (val outcome = withTimeoutOrNull(LINK_WAIT) { linked.await() }) {
        is WaEvent.PairSuccess -> null
        is WaEvent.PairError -> "WhatsApp refused the link: ${outcome.error}"
        is WaEvent.LoggedOut -> "WhatsApp ended the link (${outcome.reason})"
        else -> "WhatsApp did not confirm the code in time. Try again."
    }
}

private suspend fun LoginScope.failed(e: Exception) = failedWith(reasonFor(e))

private suspend fun LoginScope.failedWith(reason: String) = show(LoginStep.Failed("failed", reason, canRetry = true))

private suspend fun LoginScope.cancelled() = show(LoginStep.Failed("failed", "Linking cancelled", canRetry = true))

/** Plain words for each linking failure. */
internal fun reasonFor(e: Exception): String {
    val message = e.message.orEmpty()
    return when {
        "too short" in message -> {
            "That number is too short."
        }

        "international" in message -> {
            "Type the number with its country code, without a leading zero."
        }

        "websocket" in message || "not connected" in message -> {
            "Could not reach WhatsApp. Check the connection and try again."
        }

        else -> {
            "WhatsApp did not accept the link: ${message.substringAfter(": ")}"
        }
    }
}

private const val MIN_DIGITS = 7
private val LINK_WAIT = 5.minutes
