// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.demo

import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.LoginFlow
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep
import org.pingme.core.connector.TextKind
import kotlin.time.Duration.Companion.milliseconds

class DemoLoginTest {
    private val credentials = MemoryCredentialStore()
    private val controls = DemoControls().apply { update { it.copy(confirmationDelay = 5.milliseconds) } }

    private fun run(answer: (LoginStep) -> LoginResponse?): List<LoginStep> =
        runBlocking {
            val flow: LoginFlow = demoLoginFlow(controls, credentials)
            flow.steps.onEach { step -> answer(step)?.let { flow.respond(step.id, it) } }.toList()
        }

    @Test
    fun theTwoPathsTogetherShowEveryKindOfStep() {
        val qr = run { if (it is LoginStep.Choose) LoginResponse.Choice("qr") else null }
        var codeAttempts = 0
        val account =
            run { step ->
                when (step) {
                    is LoginStep.Choose -> {
                        LoginResponse.Choice("account")
                    }

                    is LoginStep.EnterText -> {
                        if (step.kind == TextKind.CODE) {
                            LoginResponse.Text(if (codeAttempts++ == 0) "000000" else "424242")
                        } else {
                            LoginResponse.Text("+15555550100")
                        }
                    }

                    is LoginStep.OpenWebView -> {
                        LoginResponse.Cookies(mapOf("session" to "x"))
                    }

                    else -> {
                        null
                    }
                }
            }
        val cancelled = run { if (it is LoginStep.Choose) LoginResponse.Cancel else null }

        val kinds = (qr + account + cancelled).map { it::class }.toSet()
        assertEquals(
            setOf(
                LoginStep.Choose::class,
                LoginStep.ShowQr::class,
                LoginStep.EnterText::class,
                LoginStep.OpenWebView::class,
                LoginStep.WaitForConfirmation::class,
                LoginStep.Failed::class,
                LoginStep.Done::class,
            ),
            kinds,
        )
        // The QR refreshes once, as real pairing codes do.
        assertEquals(
            2,
            qr
                .filterIsInstance<LoginStep.ShowQr>()
                .map { it.qrData }
                .distinct()
                .size,
        )
        // The emoji-match step carries its emoji.
        assertEquals("🦊", account.filterIsInstance<LoginStep.WaitForConfirmation>().single().emoji)
        // A wrong code is asked again with an error.
        val codes = account.filterIsInstance<LoginStep.EnterText>().filter { it.kind == TextKind.CODE }
        assertEquals(listOf(null, "That code didn't work. Try another."), codes.map { it.error })
    }

    @Test
    fun finishingSavesCredentialsAndCancellingDoesNot() {
        val done = run { if (it is LoginStep.Choose) LoginResponse.Choice("qr") else null }.last() as LoginStep.Done
        assertArrayEquals(DemoConnector.DEMO_SECRET, runBlocking { credentials.load(done.credentialRef) })

        val failed =
            run {
                if (it is LoginStep.EnterText) {
                    LoginResponse.Cancel
                } else {
                    (it as? LoginStep.Choose)?.let {
                        LoginResponse.Choice("account")
                    }
                }
            }
        assertTrue(failed.last() is LoginStep.Failed)
        assertTrue((failed.last() as LoginStep.Failed).canRetry)
    }
}
