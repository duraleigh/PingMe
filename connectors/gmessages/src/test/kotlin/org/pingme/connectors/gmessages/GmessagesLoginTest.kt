// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.gmessages

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.LoginResponse
import org.pingme.core.connector.LoginStep
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The pairing steps around the bridge: the phone checks, the sign-in page, the emoji, and failures. */
class GmessagesLoginTest {
    private val bridge = FakeGmBridge()
    private val credentials = MemoryCredentialStore()

    private class Phone(
        var installed: Boolean,
        var default: Boolean,
    ) : GmessagesChecks {
        override fun installed() = installed

        override fun isDefaultSmsApp() = default
    }

    @Test
    fun asksToInstallThenToMakeDefaultBeforeSigningIn() =
        runTest {
            val phone = Phone(installed = false, default = false)
            val flow = gmessagesLoginFlow(bridge, phone, credentials)
            val seen = mutableListOf<LoginStep>()
            val collecting = launch(Dispatchers.Default) { flow.steps.collect { synchronized(seen) { seen += it } } }
            val install = awaitStep(seen) { it is LoginStep.Fix && it.id == "install" }
            assertTrue((install as LoginStep.Fix).actionUri.startsWith("market:"))
            phone.installed = true
            flow.respond(install.id, LoginResponse.CheckAgain)
            val default = awaitStep(seen) { it is LoginStep.Fix && it.id == "default" }
            phone.default = true
            flow.respond(default.id, LoginResponse.CheckAgain)
            val web = awaitStep(seen) { it is LoginStep.OpenWebView } as LoginStep.OpenWebView
            assertEquals(bridge.signInUrl, web.url)
            assertTrue("google.com" in web.cookieDomains)
            flow.respond(web.id, LoginResponse.Cookies(FakeGmBridge.REQUIRED.associateWith { "v" }))
            val emoji = awaitStep(seen) { it is LoginStep.WaitForConfirmation && it.emoji != null }
            assertEquals("🦊", (emoji as LoginStep.WaitForConfirmation).emoji)
            val done = awaitStep(seen) { it is LoginStep.Done } as LoginStep.Done
            assertEquals(FakeGmBridge.EMAIL, done.accountName)
            assertEquals(FakeGmBridge.AUTH, credentials.load(done.credentialRef)!!.decodeToString())
            collecting.join()
        }

    @Test
    fun givingUpOnAFixEndsInFailed() =
        runTest {
            val flow = gmessagesLoginFlow(bridge, Phone(installed = false, default = true), credentials)
            val steps = mutableListOf<LoginStep>()
            val collecting = launch(Dispatchers.Default) { flow.steps.collect { synchronized(steps) { steps += it } } }
            val fix = awaitStep(steps) { it is LoginStep.Fix }
            flow.respond(fix.id, LoginResponse.Cancel)
            collecting.join()
            assertTrue(steps.last() is LoginStep.Failed)
        }

    @Test
    fun incompleteCookiesFailWithPlainWords() =
        runTest {
            val flow = gmessagesLoginFlow(bridge, AllGood, credentials)
            val steps = mutableListOf<LoginStep>()
            val collecting = launch(Dispatchers.Default) { flow.steps.collect { synchronized(steps) { steps += it } } }
            val web = awaitStep(steps) { it is LoginStep.OpenWebView }
            flow.respond(web.id, LoginResponse.Cookies(mapOf("SID" to "only")))
            collecting.join()
            val failed = steps.last() as LoginStep.Failed
            assertTrue(failed.reason, "did not finish signing in" in failed.reason)
            assertTrue(failed.canRetry)
        }

    @Test
    fun pairingErrorCodesBecomeReasons() {
        assertTrue("wrong emoji" in reasonFor(Exception("PAIR_WRONG_EMOJI: nope")))
        assertTrue("No phone" in reasonFor(Exception("PAIR_NO_DEVICES: nope")))
        assertEquals("Pairing failed: something odd", reasonFor(Exception("something odd")))
    }

    private suspend fun awaitStep(
        seen: MutableList<LoginStep>,
        matches: (LoginStep) -> Boolean,
    ): LoginStep =
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                var found: LoginStep? = null
                while (found == null) {
                    found = synchronized(seen) { seen.firstOrNull(matches) }
                    if (found == null) delay(POLL)
                }
                found
            }
        }

    private companion object {
        val POLL = 10.milliseconds
    }
}
