// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.login

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.model.AccountId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A login drawn from the demo connector's steps: every kind of step, a new account, and logging in again. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private var finished = 0

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(confirmationDelay = 200.milliseconds) }
    }

    @After
    fun tearDown() = demo.close()

    private fun show(vm: LoginViewModel) {
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
                LoginRoute(again = false, onFinish = { finished++ }, onBack = {}, viewModel = vm)
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    private fun tap(text: String) = compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)

    private fun shown(text: String) =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun type(text: String) = compose.onNode(hasSetTextAction()).performTextReplacement(text)

    @Test
    fun theQrPathEndsWithANewAccountAndSetupDone() {
        show(demo.loginViewModel(fromSetup = true))
        waitFor { shown("Scan a QR code") }
        tap("Scan a QR code")
        waitFor { compose.onAllNodesWithContentDescription("QR code to scan").fetchSemanticsNodes().isNotEmpty() }
        waitFor { finished == 1 }
        val made = runBlocking { demo.accounts.getAll() }.single()
        assertEquals(NetworkId.DEMO, made.network)
        assertTrue(
            runBlocking {
                demo.settings.app
                    .first()
                    .setupDone
            },
        )
    }

    @Test
    fun theSignInPathShowsAWrongCodeThenTheEmoji() {
        show(demo.loginViewModel())
        waitFor { shown("Sign in") }
        tap("Sign in")
        waitFor { shown("Phone number") }
        type("5551234")
        tap("Continue")
        waitFor { shown("Any code but 000000 works") }
        type("000000")
        tap("Continue")
        waitFor { shown("That code didn't work") }
        type("123456")
        tap("Continue")
        waitFor { shown("I have signed in") }
        tap("I have signed in")
        waitFor { shown("🦊") }
        waitFor { finished == 1 }
    }

    @Test
    fun loggingInAgainKeepsTheAccount() {
        val id = AccountId("demo")
        runBlocking {
            demo.seed()
            demo.accounts.updateState(id, ConnectionState.ActionNeeded("Logged out", null))
        }
        show(demo.loginViewModel(again = id))
        waitFor { shown("Scan a QR code") }
        tap("Scan a QR code")
        waitFor { finished == 1 }
        val account = runBlocking { demo.accounts.get(id) }!!
        assertNotEquals("ref", account.credentialRef)
        assertTrue(account.state is ConnectionState.Reconnecting)
        assertEquals(1, runBlocking { demo.accounts.getAll() }.size)
    }

    private companion object {
        const val TIMEOUT = 15_000L
        val STEP: Duration = Duration.ofMillis(50)
    }
}
