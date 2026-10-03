// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.pingme.connectors.telegram.td.TdError
import org.pingme.connectors.telegram.td.TelegramBridge
import org.pingme.connectors.telegram.td.TelegramClient
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginScope
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.TextKind
import org.pingme.core.connector.loginFlow
import java.io.File

/**
 * Signing in to Telegram (BUILD_PLAN.md Phase 6, network 2), Telegram's own way:
 * 1. EnterText: the phone number with its country code.
 * 2. EnterText: the code Telegram sends (to the Telegram app, or by text message).
 * 3. EnterText: the two-step password, only when the account has one.
 * 4. Done: TDLib's store moves from `telegram/link-<time>` to `telegram/<user id>`, and
 *    the credential ref `telegram/<user id>` names it.
 * Without Telegram's app credentials (api_id, api_hash) the flow says so and stops.
 */
internal fun telegramLoginFlow(
    bridge: TelegramBridge,
    credentials: CredentialStore,
    dir: File,
    apiId: Int,
    apiHash: String,
): LoginFlow =
    loginFlow {
        if (apiId == 0 || apiHash.isEmpty()) {
            return@loginFlow failedWith("This build has no Telegram app credentials, so Telegram cannot sign in.")
        }
        val states = Channel<TdApi.AuthorizationState>(Channel.UNLIMITED)
        val linkDir = File(dir, "link-${System.currentTimeMillis()}").apply { mkdirs() }
        val client =
            bridge.newClient { update ->
                (update as? TdApi.UpdateAuthorizationState)?.let { states.trySend(it.authorizationState) }
            }
        try {
            val userId = signIn(client, states, linkDir, apiId, apiHash) ?: return@loginFlow
            val me = client.send(TdApi.GetMe())
            val phone = me.phoneNumber.takeIf { it.isNotEmpty() }?.let { "+$it" } ?: "Telegram"
            closeAndWait(client, states)
            val ref = "telegram/$userId"
            moveStore(linkDir, File(dir, userId.toString()))
            credentials.save(ref, phone.toByteArray())
            show(LoginStep.Done("done", ref, accountName = phone))
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            failedWith(reasonFor(e))
            runCatching { client.close() }
        }
    }

/**
 * Walks TDLib's sign-in states, asking for what each one needs. Null when the user gave
 * up. One branch per state, and each either goes on or ends the flow.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
private suspend fun LoginScope.signIn(
    client: TelegramClient,
    states: Channel<TdApi.AuthorizationState>,
    linkDir: File,
    apiId: Int,
    apiHash: String,
): Long? {
    var error: String? = null
    while (true) {
        val state =
            withTimeoutOrNull(STATE_TIMEOUT_MS) { states.receive() }
                ?: run {
                    failedWith("Telegram did not answer. Check the connection and try again.")
                    return null
                }
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                client.send(tdlibParameters(linkDir, apiId, apiHash))
            }

            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                show(LoginStep.WaitForConfirmation("start", "", emoji = null))
                val answer =
                    ask(
                        LoginStep.EnterText(
                            "phone",
                            "Your Telegram phone number, with the country code",
                            TextKind.PHONE_NUMBER,
                            "+1 919 555 0123",
                            error,
                        ),
                    )
                if (answer !is LoginResponse.Text) return cancelled()
                error =
                    attempt(
                        client,
                        TdApi.SetAuthenticationPhoneNumber(answer.value.filter { it.isDigit() || it == '+' }, null),
                    )
            }

            is TdApi.AuthorizationStateWaitCode -> {
                val inApp = state.codeInfo.type is TdApi.AuthenticationCodeTypeTelegramMessage
                val where = if (inApp) "your other Telegram app" else "a text message"
                val answer =
                    ask(LoginStep.EnterText("code", "The code Telegram sent to $where", TextKind.CODE, null, error))
                if (answer !is LoginResponse.Text) return cancelled()
                error = attempt(client, TdApi.CheckAuthenticationCode(answer.value.trim()))
            }

            is TdApi.AuthorizationStateWaitPassword -> {
                val hint = state.passwordHint.takeIf { it.isNotBlank() }?.let { "Hint: $it" }
                val answer =
                    ask(
                        LoginStep.EnterText(
                            "password",
                            "Your Telegram two-step password",
                            TextKind.PASSWORD,
                            hint,
                            error,
                        ),
                    )
                if (answer !is LoginResponse.Text) return cancelled()
                error = attempt(client, TdApi.CheckAuthenticationPassword(answer.value))
            }

            is TdApi.AuthorizationStateReady -> {
                show(LoginStep.WaitForConfirmation("check", "Signed in. Getting your account ready…", emoji = null))
                return client.send(TdApi.GetMe()).id
            }

            is TdApi.AuthorizationStateClosed -> {
                failedWith("Telegram closed the sign-in. Try again.")
                return null
            }

            else -> {
                Unit
            }
        }
    }
}

/** Sends a sign-in answer; the plain-words reason when Telegram refused it, else null. */
private suspend fun attempt(
    client: TelegramClient,
    function: TdApi.Function<TdApi.Ok>,
): String? =
    try {
        client.send(function)
        null
    } catch (e: TdError) {
        reasonFor(e)
    }

private suspend fun closeAndWait(
    client: TelegramClient,
    states: Channel<TdApi.AuthorizationState>,
) {
    client.close()
    withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
        while (states.receive() !is TdApi.AuthorizationStateClosed) {
            // keep waiting
        }
    }
}

private suspend fun moveStore(
    from: File,
    to: File,
) = withContext(Dispatchers.IO) {
    to.deleteRecursively()
    if (!from.renameTo(to)) {
        from.copyRecursively(to, overwrite = true)
        from.deleteRecursively()
    }
}

private suspend fun LoginScope.cancelled(): Long? {
    show(LoginStep.Failed("failed", "Sign-in cancelled", canRetry = true))
    return null
}

private suspend fun LoginScope.failedWith(reason: String) = show(LoginStep.Failed("failed", reason, canRetry = true))

/** Plain words for each sign-in failure. */
internal fun reasonFor(e: Exception): String {
    val message = e.message.orEmpty()
    return when {
        "PHONE_CODE_INVALID" in message -> "That code did not work. Check it and try again."
        "PHONE_CODE_EXPIRED" in message -> "That code has expired. Start again for a fresh one."
        "PASSWORD_HASH_INVALID" in message -> "That password did not work."
        "PHONE_NUMBER_INVALID" in message -> "That is not a full number with a country code."
        "PHONE_NUMBER_BANNED" in message -> "Telegram has banned that number."
        "FLOOD" in message -> "Telegram asked PingMe to wait before trying again. Try in a while."
        "API_ID_INVALID" in message -> "This build's Telegram app credentials are wrong."
        else -> "Telegram could not sign in: ${message.substringAfter(": ")}"
    }
}

private const val STATE_TIMEOUT_MS = 90_000L
private const val CLOSE_TIMEOUT_MS = 5_000L
