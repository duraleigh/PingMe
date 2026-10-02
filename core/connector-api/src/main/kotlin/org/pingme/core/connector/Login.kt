// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * A login as a series of steps (BUILD_PLAN.md P1.3). The UI collects [steps], renders
 * each one, and answers with [respond]. It never needs to know which network it is.
 */
interface LoginFlow {
    val steps: Flow<LoginStep>

    suspend fun respond(
        stepId: String,
        value: LoginResponse,
    )
}

/** What the UI shows. A step with the same [id] as the current one replaces it (a new QR). */
sealed interface LoginStep {
    val id: String

    /** Show [qrData] as a QR code to scan in the other app. [canShare] offers a second screen. */
    data class ShowQr(
        override val id: String,
        val qrData: String,
        val hint: String,
        val canShare: Boolean,
    ) : LoginStep

    data class EnterText(
        override val id: String,
        val label: String,
        val kind: TextKind,
        val hint: String?,
        /** Set when the previous answer was wrong ("That code didn't work"). */
        val error: String?,
    ) : LoginStep

    /**
     * Open [url] in an in-app browser and answer with the cookies for [cookieDomains]. When
     * the browser reaches a page whose address starts with [finishedUrlPrefix], the UI
     * answers by itself; otherwise the user says when they are done.
     */
    data class OpenWebView(
        override val id: String,
        val url: String,
        val cookieDomains: List<String>,
        val finishedUrlPrefix: String? = null,
    ) : LoginStep

    /**
     * Waiting for the user to confirm in another app. [emoji] is set for the emoji-match
     * step of Google account pairing, shown at display size (UI_DESIGN.md 3.6).
     */
    data class WaitForConfirmation(
        override val id: String,
        val hint: String,
        val emoji: String?,
    ) : LoginStep

    /** A decision, such as QR pairing versus Google account pairing (BUILD_PLAN.md P3.2). */
    data class Choose(
        override val id: String,
        val title: String,
        val options: List<Option>,
    ) : LoginStep {
        data class Option(
            val id: String,
            val label: String,
            val description: String?,
        )
    }

    /**
     * Something on the phone must change before the login can go on (DESIGN.md 5.4 step
     * 1: Google Messages installed and the default SMS app). [actionUri] is what the fix
     * button opens: `package:<name>` launches that app, `market:<name>` its store page,
     * `settings:<action>` a system settings screen, anything else is viewed as a URI.
     * "Check again" answers with [LoginResponse.CheckAgain].
     */
    data class Fix(
        override val id: String,
        val title: String,
        val detail: String,
        val actionLabel: String,
        val actionUri: String,
    ) : LoginStep

    /** The login could not finish. [canRetry] offers to start again. */
    data class Failed(
        override val id: String,
        val reason: String,
        val canRetry: Boolean,
    ) : LoginStep

    /** Logged in; the credentials were saved under [credentialRef]. */
    data class Done(
        override val id: String,
        val credentialRef: String,
        val accountName: String,
    ) : LoginStep
}

enum class TextKind {
    PHONE_NUMBER,
    CODE,
    PASSWORD,
    TOKEN,
    TEXT,
}

sealed interface LoginResponse {
    data class Text(
        val value: String,
    ) : LoginResponse

    data class Choice(
        val optionId: String,
    ) : LoginResponse

    data class Cookies(
        val cookies: Map<String, String>,
    ) : LoginResponse

    /** "Show the QR on another screen" for single-phone setups. */
    data object ShareRequested : LoginResponse

    /** The user says a [LoginStep.Fix] is done; the connector checks again. */
    data object CheckAgain : LoginResponse

    data object Cancel : LoginResponse
}

/** What a connector's login script can do: show a step, or show one and wait for the answer. */
interface LoginScope {
    suspend fun show(step: LoginStep)

    suspend fun ask(step: LoginStep): LoginResponse
}

/**
 * Builds a [LoginFlow] from a script. Answers are matched to the step they were given for;
 * an answer to an older step is ignored. The flow ends when the script returns.
 */
fun loginFlow(script: suspend LoginScope.() -> Unit): LoginFlow =
    object : LoginFlow {
        private val responses = Channel<Pair<String, LoginResponse>>(Channel.UNLIMITED)

        override val steps: Flow<LoginStep> =
            channelFlow {
                val scope =
                    object : LoginScope {
                        override suspend fun show(step: LoginStep) = send(step)

                        override suspend fun ask(step: LoginStep): LoginResponse {
                            send(step)
                            while (true) {
                                val (id, value) = responses.receive()
                                if (id == step.id) return value
                            }
                        }
                    }
                scope.script()
            }

        override suspend fun respond(
            stepId: String,
            value: LoginResponse,
        ) = responses.send(stepId to value)
    }
