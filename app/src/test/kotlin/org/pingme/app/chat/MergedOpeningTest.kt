// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.connector.chat
import org.pingme.core.model.AccountId
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A merged chat opens on the network its unread message is on, even on the first visit since
 * the app started (owner, 2026-10-04: from the WhatsApp list it opened on Google Messages
 * although the unread message was on WhatsApp; the member list had not loaded before the
 * chat was marked read).
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp-xhdpi")
class MergedOpeningTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private val other = AccountId("demo-wa")

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(liveActivity = false) }
        runBlocking { demo.seed(history = false) }
    }

    @After
    fun tearDown() = demo.close()

    @Test
    fun aFirstVisitOpensOnTheUnreadNetworkAndOnlyThenMarksItRead() {
        val sam = demo.account.id.chat("sam")
        val samOnOther = other.chat("sam-wa")
        val merged =
            runBlocking {
                demo.accounts.upsert(demo.account.copy(id = other))
                demo.actions.setRead(sam, read = true)
                val base = demo.chats.get(sam)!!
                demo.chats.upsert(
                    base.copy(id = samOnOther, accountId = other, unreadCount = 1, isPinned = false, pinOrder = null),
                )
                demo.mergeService.merge(listOf(sam, samOnOther))
            }
        val vm = demo.chatViewModelFor(merged)
        // The screen subscribes to the state and reports the chat visible, as on the phone.
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) { ChatRoute(vm.chatId, onBack = {}, viewModel = vm) }
        }
        vm.visible(true)
        waitUntil { vm.state.value.filter == other }
        waitUntil { runBlocking { demo.chats.get(samOnOther)!!.unreadCount == 0 } }
        assertEquals(other, vm.state.value.filter)
    }

    private fun waitUntil(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
            condition()
        }

    private companion object {
        const val TIMEOUT = 15_000L
        const val STEP_MS = 50L
    }
}
