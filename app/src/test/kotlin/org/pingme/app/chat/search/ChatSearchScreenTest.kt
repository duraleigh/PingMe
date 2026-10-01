// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.search

import android.os.Looper
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.chat.ChatRoute
import org.pingme.app.chat.ChatViewModel
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.model.MessageKind
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.ZoneId

/** Search in chat: words, type chips, the sender filter, and jumping to a result (UI_DESIGN.md 10.14). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ChatSearchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private lateinit var vm: ChatViewModel

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(liveActivity = false) }
        runBlocking { demo.seed() }
        vm = demo.chatViewModel("design")
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) { ChatRoute(vm.chatId, onBack = {}, viewModel = vm) }
        }
        waitFor {
            vm.state.value.items
                .isNotEmpty()
        }
        compose.onNode(hasContentDescription("More")).performClick()
        compose.onNodeWithText("Search in chat").performClick()
        waitFor { vm.search.state.value.open }
    }

    @After
    fun tearDown() = demo.close()

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun wordsFindAMessageAndTappingItJumpsThere() {
        compose.onNode(hasTestTag(SEARCH_FIELD)).performTextInput("standup")
        waitFor { vm.search.state.value.results.size == 1 }
        compose.onNode(hasTestTag(SEARCH_RESULTS)).assertExists()
        compose
            .onNode(
                hasText("Standup moved to 10", substring = true) and hasAnyAncestor(hasTestTag(SEARCH_RESULTS)),
            ).performClick()
        waitFor { !vm.search.state.value.open && vm.jumps.request.value == null }
        assertFalse(compose.onAllNodes(hasTestTag(SEARCH_RESULTS)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun thePhotosChipShowsTheChatsPictures() {
        compose.onNodeWithText("Photos").performClick()
        waitFor {
            vm.search.state.value.results
                .isNotEmpty()
        }
        assertEquals(
            setOf(MessageKind.IMAGE),
            vm.search.state.value.results
                .map { it.kind }
                .toSet(),
        )
    }

    @Test
    fun theSenderFilterKeepsOnePersonsMessages() {
        compose.onNode(hasTestTag(SEARCH_CHIPS)).performScrollToNode(hasText("From anyone"))
        compose.onNodeWithText("From anyone").performClick()
        // The menu's line, not the name on Leo's bubble behind it.
        compose.onNode(hasText("Leo Park") and hasAnyAncestor(isPopup())).performClick()
        waitFor {
            vm.search.state.value.results
                .isNotEmpty()
        }
        assertEquals(
            1,
            vm.search.state.value.results
                .map { it.senderId }
                .toSet()
                .size,
        )
        waitFor { shows("Standup moved to 10") }
    }

    @Test
    fun aDatePicksMidnightWhereThePhoneIs() {
        val day = startOfDay(MAY_FIRST_UTC)
        val local =
            java.time.Instant
                .ofEpochMilli(day.toEpochMilliseconds())
                .atZone(ZoneId.systemDefault())
        assertEquals(
            listOf(2026, 5, 1, 0, 0),
            listOf(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute),
        )
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val MAY_FIRST_UTC = 1_777_593_600_000L
        val STEP: Duration = Duration.ofMillis(50)
    }
}
