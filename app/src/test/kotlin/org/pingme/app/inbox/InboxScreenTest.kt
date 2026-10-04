// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
import org.pingme.core.connector.chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.service.IncomingReaction
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** The inbox against the demo network (BUILD_PLAN.md P2.3, P2.8). */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class InboxScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private var appearance by mutableStateOf(Appearance(mode = ThemeMode.LIGHT, motion = MotionIntensity.FULL))
    private val opened = mutableListOf<ChatId>()
    private var searched = false

    private fun id(remote: String) = demo.account.id.chat(remote)

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        runBlocking { demo.seed() }
    }

    @After
    fun tearDown() = demo.close()

    private fun show(vm: InboxViewModel = demo.inboxViewModel()) {
        compose.setContent {
            PingMeTheme(appearance) {
                InboxRoute(
                    InboxNavigation(
                        onOpenChat = { opened += it },
                        onSearch = { searched = true },
                        onNewChat = {},
                        onNewGroup = {},
                        onFix = {},
                        menu = MenuActions({}, {}, {}),
                    ),
                    viewModel = vm,
                )
            }
        }
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("Alex Kim").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun chat(remote: String) = runBlocking { demo.chats.get(id(remote))!! }

    /** Waits for [condition], letting work queued on the main thread run, as it would on a phone. */
    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            // Moving the clock on, not just running what is due, keeps slow machines from stalling here.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
            condition()
        }

    @Test
    fun showsTheDemoCastButNotRequests() {
        show()
        compose.onNodeWithText("Design team").assertExists()
        compose.onNodeWithText("Mom").assertExists()
        compose.onNodeWithText("Casey Nguyen").assertDoesNotExist()
        compose.onNodeWithText("Sam Ortiz").performClick()
        assertEquals(listOf(id("sam")), opened)
    }

    @Test
    fun aRowWhoseLastMessageIsYoursShowsItsMark() {
        show()
        // Dad's last message is yours and was read; Mom's is hers, so it carries no mark. A row
        // reads as one item, so its name and its mark are on the same node.
        compose.onNode(hasText("Dad") and hasContentDescription("Read")).assertExists()
        compose.onNode(hasText("Mom") and hasContentDescription("Read")).assertDoesNotExist()
        compose.onNode(hasText("Mom") and hasContentDescription("Delivered")).assertDoesNotExist()
    }

    @Test
    fun theBottomBarsItemsAreSpreadEvenlyAcrossIt() {
        show()
        val bar = compose.onNode(hasTestTag(INBOX_BAR)).getBoundsInRoot()
        // More is always the last button (owner, 2026-10-03).
        val labels = listOf("All", "Unread", "Demo", "More")
        labels.forEachIndexed { i, label ->
            val item = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(INBOX_BAR))).getBoundsInRoot()
            val centre = (item.left + item.right) / 2
            val expected = bar.left + (bar.right - bar.left) * ((i + 0.5f) / labels.size)
            assertTrue("$label centred at $centre, expected $expected", abs((centre - expected).value) < 2f)
        }
    }

    @Test
    fun withAllRemovedTheInboxOpensOnTheFirstButton() {
        val vm = demo.inboxViewModel()
        show(vm)
        vm.setBarItems(listOf(InboxBarItem.Unread, InboxBarItem.Network(NetworkId.DEMO)))
        val state = vm.state
        waitFor { state.value.bar.none { it.item == null } }
        assertEquals(InboxBarItem.Unread, state.value.selected)
        compose.onNode(hasText("All") and hasAnyAncestor(hasTestTag(INBOX_BAR))).assertDoesNotExist()
        // Unread is showing: a chat with nothing unread is not in the list.
        compose.onNodeWithText("Alex Kim").assertDoesNotExist()
    }

    @Test
    fun pressAndHoldPinsAChatIntoTheGrid() {
        show()
        compose.onNodeWithText("Dad").performTouchInput { longClick() }
        compose.onNodeWithText("Pin to top").performClick()
        waitFor { chat("dad").isPinned }
        // Pinned chats leave the list: its preview line goes, the tile stays.
        waitFor { compose.onAllNodesWithText("You: Will do tonight").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Dad").assertExists()
    }

    @Test
    fun swipingRightMarksReadAndLeftArchivesWithUndo() {
        show()
        assertTrue(chat("mom").unreadCount > 0)
        compose.onNodeWithText("Mom").performTouchInput { swipeRight() }
        waitFor { chat("mom").unreadCount == 0 }

        compose.onNodeWithText("Alex Kim").performTouchInput { swipeLeft() }
        waitFor { chat("alex").isArchived }
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").performClick()
        waitFor { !chat("alex").isArchived }
    }

    @Test
    fun theUnreadButtonShowsOnlyUnreadChats() {
        show()
        compose.onNodeWithText("Unread").performClick()
        waitFor { compose.onAllNodesWithText("Dad").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Mom").assertExists()
        compose.onNodeWithText("All").performClick()
        waitFor { compose.onAllNodesWithText("Dad").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theStatusPillCyclesTheDemoAndTheChipExplains() {
        show()
        compose.onNodeWithText("Connected").performClick()
        waitFor { runBlocking { demo.accounts.get(demo.account.id)!!.state is ConnectionState.Reconnecting } }
        waitFor { compose.onAllNodesWithText("Demo reconnecting").fetchSemanticsNodes().isNotEmpty() }
        // The pill comes first in the layout, the chip under the bar second.
        compose.onAllNodesWithText("Demo reconnecting")[0].performClick()
        waitFor { compose.onAllNodesWithText("Demo needs attention, tap to fix").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anIncomingReactionFlipsTheRowAndComesBack() {
        show()
        demo.reactions.emit(IncomingReaction(id("sam"), "🎉"))
        waitFor { compose.onAllNodesWithText("🎉").fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodesWithText("🎉").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun searchAndTheMenusAreReachable() {
        show()
        compose
            .onNode(
                hasText("Search") or
                    androidx.compose.ui.test
                        .hasContentDescription("Search"),
            ).performClick()
        assertTrue(searched)
        compose
            .onNode(
                androidx.compose.ui.test
                    .hasContentDescription("Account and settings"),
            ).performClick()
        compose.onNodeWithText("Archived").assertExists()
        compose.onNodeWithText("Requests").assertExists()
        assertFalse(
            "Settings waits for P2.6",
            compose
                .onNodeWithText(
                    "Settings",
                ).fetchSemanticsNode()
                .config
                .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                .not(),
        )
    }

    /** Renders the inbox to build/screenshots/inbox-light.png and inbox-dark.png. */
    @Test
    fun screenshots() {
        runBlocking {
            listOf(
                "sam",
                "design",
                "mom",
                "dad",
                "taylor",
                "book-club",
            ).forEach { demo.actions.setPinned(id(it), true) }
            demo.actions.setMuted(id("gym"), true)
        }
        show()
        demo.typing.set(
            id("design"),
            demo.account.id.let {
                org.pingme.core.model
                    .PersonId(it.value + "/priya")
            },
            true,
        )
        compose.waitForIdle()
        save("inbox-light")
        appearance = appearance.copy(mode = ThemeMode.DARK)
        compose.waitForIdle()
        save("inbox-dark")
    }

    /** Tablets and unfolded foldables get the rail instead of the bottom bar (UI_DESIGN.md 3.7). */
    @Test
    @Config(qualifiers = "w900dp-h700dp-xhdpi")
    fun wideScreensUseTheRail() {
        show()
        compose.onNodeWithText("Unread").assertExists()
        save("inbox-wide")
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val STEP_MS = 50L
    }
}
