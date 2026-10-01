// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.core.connector.chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.ui.theme.PingMeTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * The screens the inbox leads to, against the demo network (BUILD_PLAN.md P2.3, P2.8). Same
 * graphics mode as InboxScreenTest: tests that load the SQLite natives must share one sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InboxDestinationsTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private val opened = mutableListOf<ChatId>()

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(liveActivity = false) }
        runBlocking { demo.seed() }
    }

    @After
    fun tearDown() = demo.close()

    // Moves the main thread's clock on as it waits, so progress does not depend on how busy the machine is.
    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
            condition()
        }

    private fun waitForText(text: String) =
        waitFor { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun requestsCanBeAcceptedIntoPrimary() {
        compose.setContent {
            PingMeTheme {
                ChatListRoute(
                    {},
                    { opened += it },
                    viewModel = demo.listViewModel(ChatListRoute(ListKind.REQUESTS)),
                )
            }
        }
        waitForText("Casey Nguyen")
        compose.onNodeWithText("Accept").performClick()
        val casey = demo.account.id.chat("casey")
        waitFor { runBlocking { demo.chats.get(casey)?.folder } == ChatFolder.PRIMARY }
        waitFor { compose.onAllNodesWithText("Casey Nguyen").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun archivedChatsShowAndOpen() {
        runBlocking { demo.actions.setArchived(demo.account.id.chat("dad"), true) }
        compose.setContent {
            PingMeTheme {
                ChatListRoute(
                    {},
                    { opened += it },
                    viewModel = demo.listViewModel(ChatListRoute(ListKind.ARCHIVED)),
                )
            }
        }
        waitForText("Dad")
        compose.onNodeWithText("Dad").performClick()
        assertEquals(listOf(demo.account.id.chat("dad")), opened)
    }

    @Test
    fun searchFindsChatsByNameAndMessagesByText() {
        val vm = demo.searchViewModel()
        compose.setContent { PingMeTheme { SearchRoute({}, { opened += it }, viewModel = vm) } }
        compose.onNode(hasSetTextAction()).performTextInput("seat")
        waitForText("I saved you a seat")
        compose.onNodeWithText("I saved you a seat").performClick()
        assertEquals(listOf(demo.account.id.chat("sam")), opened)
    }

    @Test
    fun aNewChatOpensOnTheDemoNetwork() {
        compose.setContent {
            PingMeTheme { NewChatRoute({}, { opened += it }, viewModel = demo.newChatViewModel(group = false)) }
        }
        waitForText("Name, number, or username")
        compose.onNode(hasSetTextAction()).performTextInput("+15550123")
        waitForText("Start a chat with +15550123")
        compose.onNodeWithText("Start a chat with +15550123").performClick()
        waitFor { opened.isNotEmpty() }
        assertEquals("+15550123", runBlocking { demo.chats.get(opened.single())?.title })
    }

    @Test
    fun aNewGroupIsMadeWithItsMembers() {
        val vm = demo.newChatViewModel(group = true)
        compose.setContent {
            PingMeTheme { NewChatRoute({}, { opened += it }, viewModel = vm) }
        }
        waitForText("Group name")
        compose.onNodeWithText("Group name").performTextInput("Trip")
        compose.onNodeWithText("Name, number, or username").performTextInput("ana")
        waitForText("Add ana")
        compose.onNodeWithText("Add ana").performClick()
        // The button turns on once the member shows as a chip.
        waitFor { compose.onAllNodes(hasText("Make the group") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        // Through the button's own click action, so a layout still settling cannot move the tap.
        compose.onNodeWithText("Make the group").performSemanticsAction(SemanticsActions.OnClick)
        waitFor { opened.isNotEmpty() }
        val group = runBlocking { demo.chats.get(opened.single()) }!!
        assertEquals("Trip" to ChatKind.GROUP, group.title to group.kind)
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val STEP_MS = 50L
    }
}
