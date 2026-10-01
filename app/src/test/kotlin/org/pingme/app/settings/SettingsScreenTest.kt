// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.model.AccountId
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Settings: the page list, accounts, privacy, reactions, and motion (BUILD_PLAN.md P2.6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private lateinit var vm: SettingsViewModel
    private val opened = mutableListOf<SettingsPage>()
    private val openedAccounts = mutableListOf<AccountId>()

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(liveActivity = false) }
        runBlocking { demo.seed() }
        vm = demo.settingsViewModel()
    }

    @After
    fun tearDown() = demo.close()

    private fun show(
        page: SettingsPage,
        account: AccountId? = null,
    ) {
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
                SettingsRoute(
                    page,
                    SettingsNavigation({}, { opened += it }, { openedAccounts += it }, {}),
                    account = account,
                    viewModel = vm,
                )
            }
        }
        waitFor {
            vm.state.value.accounts
                .isNotEmpty()
        }
    }

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    private fun tap(text: String) = compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun homeListsTheBuiltPagesAndOpensThem() {
        show(SettingsPage.HOME)
        assertTrue(shown("Accounts"))
        // Pages still to come in this step stay off the list.
        assertFalse(shown("Backup"))
        tap("Privacy")
        assertEquals(listOf(SettingsPage.PRIVACY), opened)
    }

    @Test
    fun accountListOpensAnAccount() {
        show(SettingsPage.ACCOUNTS)
        tap("Demo")
        assertEquals(listOf(demo.account.id), openedAccounts)
    }

    @Test
    fun anAccountCanBeKeptOutOfTheInbox() {
        show(SettingsPage.ACCOUNT, demo.account.id)
        tap("Show in inbox")
        waitFor { runBlocking { demo.accounts.get(demo.account.id)?.showInInbox == false } }
    }

    @Test
    fun readReceiptsCanBeTurnedOff() {
        show(SettingsPage.PRIVACY)
        assertTrue(vm.state.value.app.privacy.readReceipts)
        tap("Send read receipts")
        waitFor { !vm.state.value.app.privacy.readReceipts }
    }

    @Test
    fun linkPreviewsCanBeLimitedToWifi() {
        show(SettingsPage.PRIVACY)
        tap("Only on Wi-Fi")
        waitFor { vm.state.value.app.privacy.linkPreviews == org.pingme.core.model.LinkPreviewMode.WIFI_ONLY }
    }

    @Test
    fun aSpecialEmojiCanBeRemoved() {
        runBlocking { demo.settings.updateApp { it.copy(specialEmoji = setOf("🤖")) } }
        show(SettingsPage.REACTIONS)
        waitFor { shown("🤖") }
        tap("🤖")
        waitFor {
            vm.state.value.app.specialEmoji
                .isEmpty()
        }
    }

    @Test
    fun flippyReactionsCanBeTurnedOff() {
        show(SettingsPage.MOTION)
        tap("Flippy reactions")
        waitFor { vm.state.value.app.flippyReactions == false }
    }

    @Test
    fun flippyFollowsMotionUntilChosen() {
        assertTrue(flippyOn(null, MotionIntensity.FULL))
        assertTrue(flippyOn(null, MotionIntensity.EXTRA))
        assertFalse(flippyOn(null, MotionIntensity.SUBTLE))
        assertFalse(flippyOn(null, MotionIntensity.OFF))
        assertTrue(flippyOn(true, MotionIntensity.OFF))
        assertFalse(flippyOn(false, MotionIntensity.FULL))
    }

    private companion object {
        const val TIMEOUT = 15_000L
        val STEP: Duration = Duration.ofMillis(50)
    }
}
