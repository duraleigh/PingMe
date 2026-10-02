// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.instagram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.pingme.connectors.instagram.bridge.IgBridge
import org.pingme.connectors.instagram.bridge.IgError
import org.pingme.connectors.instagram.bridge.igJson
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginScope
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.TextKind
import org.pingme.core.connector.loginFlow

/**
 * Signing in to Instagram (BUILD_PLAN.md Phase 6, network 2): the instagram.com sign-in
 * page in the in-app browser; its cookies (sessionid, csrftoken, ds_user_id) are the
 * credential, saved under `instagram/<user id>`. The session is checked once before
 * Done, so a sign-in that did not finish says so at once.
 */
internal fun instagramLoginFlow(
    bridge: IgBridge,
    credentials: CredentialStore,
): LoginFlow =
    loginFlow {
        val answer = ask(LoginStep.OpenWebView("web", SIGN_IN_URL, COOKIE_DOMAINS, finishedUrlPrefix = null))
        if (answer !is LoginResponse.Cookies) return@loginFlow cancelled()
        var cookies = answer.cookies.filterKeys { it in WANTED }
        if (REQUIRED.any { cookies[it].isNullOrEmpty() }) {
            // The page gave no sign-in (not finished, or it would not show): the cookies can be
            // pasted from a browser instead, as "name=value; name=value" or the JSON form.
            val pasted =
                ask(
                    LoginStep.EnterText(
                        "paste",
                        "Instagram did not finish signing in here. Sign in to instagram.com in a browser and " +
                            "paste its cookies (sessionid, csrftoken, ds_user_id)",
                        TextKind.TOKEN,
                        "sessionid=…; csrftoken=…; ds_user_id=…",
                        null,
                    ),
                )
            if (pasted !is LoginResponse.Text) return@loginFlow cancelled()
            cookies = parseCookies(pasted.value).filterKeys { it in WANTED }
        }
        val missing = REQUIRED.filter { cookies[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            return@loginFlow failedWith(
                "Those cookies are missing ${missing.joinToString()}. Sign in again and try once more.",
            )
        }
        show(LoginStep.WaitForConfirmation("check", "Checking the sign-in with Instagram…", emoji = null))
        val json = igJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), cookies)
        val userId = cookies.getValue("ds_user_id")
        try {
            // Opening a session checks the cookies are complete; the connector connects later.
            withContext(Dispatchers.IO) { bridge.newSession(json) { } }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            return@loginFlow failedWith(reasonFor(e))
        }
        val ref = "instagram/$userId"
        credentials.save(ref, json.toByteArray())
        show(LoginStep.Done("done", ref, accountName = "Instagram"))
    }

private suspend fun LoginScope.failedWith(reason: String) = show(LoginStep.Failed("failed", reason, canRetry = true))

private suspend fun LoginScope.cancelled() = show(LoginStep.Failed("failed", "Sign-in cancelled", canRetry = true))

internal fun reasonFor(e: Exception): String =
    when (IgError.codeOf(e)) {
        IgError.LOGGED_OUT -> "Instagram did not accept the sign-in. Sign in again, all the way to the home feed."
        else -> "Could not reach Instagram: ${e.message.orEmpty().substringAfter(": ")}"
    }

/** "name=value; name=value" (a Cookie header), a cURL command carrying one, or a JSON object. */
internal fun parseCookies(text: String): Map<String, String> {
    val trimmed = text.trim()
    if (trimmed.startsWith("{")) {
        return runCatching {
            igJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), trimmed)
        }.getOrDefault(emptyMap())
    }
    val header = Regex("""(?i)cookie:?\s*["']?([^"'\n]+)""").find(trimmed)?.groupValues?.get(1) ?: trimmed
    return header
        .split(';')
        .mapNotNull { pair -> pair.trim().split("=", limit = 2).takeIf { it.size == 2 } }
        .associate { (name, value) -> name.trim() to value.trim().trim('"') }
}

internal const val SIGN_IN_URL = "https://www.instagram.com/accounts/login/"
internal val COOKIE_DOMAINS = listOf("instagram.com", ".instagram.com", "www.instagram.com")
internal val REQUIRED = listOf("sessionid", "csrftoken", "ds_user_id")
internal val WANTED = REQUIRED + listOf("mid", "ig_did", "rur", "shbid", "shbts", "datr", "dpr")
