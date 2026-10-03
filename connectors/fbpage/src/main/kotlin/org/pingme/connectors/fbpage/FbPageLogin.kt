// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.fbpage

import kotlinx.coroutines.CancellationException
import org.pingme.core.connector.CredentialStore
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.TextKind
import org.pingme.core.connector.loginFlow
import java.io.IOException

/**
 * Connecting a Facebook Page (BUILD_PLAN.md Phase 6, network 7; DESIGN.md 5.2): the
 * owner creates a Meta developer app, connects the Page, and pastes the Page access token
 * here. The token is checked with Meta once, then saved under `fbpage/<page id>`.
 */
internal fun fbPageLoginFlow(
    graph: PageGraph,
    credentials: CredentialStore,
): LoginFlow =
    loginFlow {
        var error: String? = null
        while (true) {
            val answer =
                ask(
                    LoginStep.EnterText(
                        "token",
                        "Paste the Page access token from your Meta developer app (Messenger settings > " +
                            "Access tokens > Generate token for the Page)",
                        TextKind.TOKEN,
                        "EAAB…",
                        error,
                    ),
                )
            if (answer !is LoginResponse.Text) {
                return@loginFlow show(LoginStep.Failed("failed", "Connecting cancelled", canRetry = true))
            }
            show(LoginStep.WaitForConfirmation("check", "Checking the token with Facebook…", emoji = null))
            when (val outcome = checkToken(graph, credentials, answer.value)) {
                is TokenOutcome.Again -> error = outcome.reason
                is TokenOutcome.Finished -> return@loginFlow show(outcome.step)
            }
        }
    }

private sealed interface TokenOutcome {
    data class Again(
        val reason: String,
    ) : TokenOutcome

    data class Finished(
        val step: LoginStep,
    ) : TokenOutcome
}

/** Asks Meta whose token this is; a good one is saved and the flow is done. */
private suspend fun checkToken(
    graph: PageGraph,
    credentials: CredentialStore,
    pasted: String,
): TokenOutcome {
    val token = pasted.trim().removePrefix("access_token=").trim()
    if (token.isEmpty()) return TokenOutcome.Again("Paste the token first")
    val page =
        try {
            graph.me(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GraphException) {
            return TokenOutcome.Again("Facebook did not accept that token: ${e.message}")
        } catch (e: IOException) {
            return TokenOutcome.Finished(
                LoginStep.Failed("failed", "Could not reach Facebook: ${e.message}", canRetry = true),
            )
        }
    val ref = "fbpage/${page.id}"
    credentials.save(ref, "${page.id}\n${page.name}\n$token".toByteArray())
    return TokenOutcome.Finished(LoginStep.Done("done", ref, accountName = "Page: ${page.name}"))
}

/** The saved credential: the Page id, its name, and the token. */
internal data class PageCredential(
    val pageId: String,
    val pageName: String,
    val token: String,
) {
    companion object {
        private const val PARTS = 3

        fun parse(secret: ByteArray): PageCredential {
            val lines = secret.decodeToString().split('\n', limit = PARTS)
            require(lines.size == PARTS) { "The saved Page token is unreadable" }
            return PageCredential(lines[0], lines[1], lines[2])
        }
    }
}
