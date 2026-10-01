// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.setup

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.model.NetworkId
import org.pingme.core.model.TextingMode
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** First-run setup: mode, permissions with reasons, then the first network (BUILD_PLAN.md P2.7). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private lateinit var vm: SetupViewModel
    private val logins = mutableListOf<NetworkId>()
    private var done = 0

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        vm = demo.setupViewModel()
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
                SetupRoute(SetupNavigation({ logins += it }, { done++ }, {}), viewModel = vm)
            }
        }
    }

    @After
    fun tearDown() = demo.close()

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    private fun tap(text: String) = compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // Skips every permission, whatever pages this Android version has.
    private fun skipToNetworks() {
        tap("Next")
        while (!shown("Connect your first network")) {
            tap("Not now")
            compose.waitForIdle()
        }
    }

    @Test
    fun googleMessagesModeIsRecommendedAndNativeSmsCanBePicked() {
        assertTrue(shown("Recommended"))
        tap("Native SMS mode")
        waitFor {
            runBlocking {
                demo.settings.app
                    .first()
                    .textingMode == TextingMode.NATIVE_SMS
            }
        }
    }

    @Test
    fun eachPermissionSaysWhy() {
        tap("Next")
        waitFor { vm.page.value == 1 }
        val pages = vm.pages
        if (SetupPage.NOTIFICATIONS in pages) assertTrue(shown("Get told about new messages"))
        assertTrue(SetupPage.BATTERY in pages && SetupPage.CONTACTS in pages)
    }

    @Test
    fun theFirstNetworkShowsItsRiskAndStartsItsLogin() {
        skipToNetworks()
        assertTrue(shown("Pretend messages to try PingMe. Nothing leaves your phone."))
        tap("Demo")
        assertEquals(listOf(NetworkId.DEMO), logins)
    }

    @Test
    fun setupCanBeLeftForLater() {
        skipToNetworks()
        tap("Set up later")
        waitFor { done == 1 }
        assertTrue(
            runBlocking {
                demo.settings.app
                    .first()
                    .setupDone
            },
        )
    }

    @Test
    fun backGoesToThePageBefore() {
        tap("Next")
        waitFor { vm.page.value == 1 }
        assertTrue(vm.back())
        assertEquals(0, vm.page.value)
        assertTrue(!vm.back())
    }

    private companion object {
        const val TIMEOUT = 15_000L
        val STEP: Duration = Duration.ofMillis(50)
    }
}
