// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.chat.ChatRequest
import org.pingme.app.chat.search.SearchType
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.connector.chat
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.time.Duration.Companion.hours

/** Chat details: name, mute, privacy, reactions, delete, and handing search to the chat (UI_DESIGN.md 3.4). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatDetailsScreenTest {
    // Runs on Robolectric's default screen: with a set screen size, a text field in a dialog never settles.
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private lateinit var vm: ChatDetailsViewModel
    private var backToChat = 0
    private var left = 0
    private val sam get() = demo.account.id.chat("sam")

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(liveActivity = false) }
        runBlocking { demo.seed() }
        vm = demo.detailsViewModel("sam")
    }

    private fun showScreen() {
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
                ChatDetailsRoute(vm.chatId, DetailsNavigation({}, { backToChat++ }, { left++ }), viewModel = vm)
            }
        }
        waitFor { vm.state.value.chat != null }
    }

    @After
    fun tearDown() = demo.close()

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    private fun chat() = runBlocking { demo.chats.get(sam) }

    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    @Test
    fun theNameCanBeChangedHereOnly() {
        showScreen()
        compose.onNodeWithText("Sam Ortiz").assertExists()
        compose
            .onNode(
                androidx.compose.ui.test
                    .hasContentDescription("Change name"),
            ).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Sam (work)")
        compose.onNodeWithText("OK").performClick()
        waitFor { chat()?.nameOverride == "Sam (work)" }
    }

    @Test
    fun muteForEightHours() {
        showScreen()
        scrollTo("Mute for…")
        compose.onNodeWithText("Mute for…").performClick()
        compose.onNodeWithText("8 hours").performClick()
        waitFor { chat()?.isMuted == true }
        val until = chat()!!.muteUntil!!
        assertTrue(
            "about eight hours from now",
            until > kotlin.time.Clock.System
                .now() + 7.hours,
        )
    }

    @Test
    fun obscureAndOwnReactionsAreKept() {
        showScreen()
        scrollTo("Obscure messages")
        compose.onNodeWithText("Obscure messages").performClick()
        waitFor { chat()?.isObscured == true }
        scrollTo("This chat's own set")
        // Pressed the way a screen reader presses it, so the small test screen's edges do not matter.
        compose.onNodeWithText("This chat's own set").performSemanticsAction(SemanticsActions.OnClick)
        waitFor { runBlocking { demo.overrides.get(sam).quickReactions } != null }
    }

    @Test
    fun seeAllHandsSearchToTheChat() {
        showScreen()
        compose.onNodeWithText("Links").performClick()
        compose.onNodeWithText("Files").performClick()
        compose.onNodeWithText("Media").performClick()
        // Sam's chat has no media, so the chat is asked through the Search button instead.
        compose.onNodeWithText("Search").performClick()
        waitFor { backToChat == 1 }
        assertEquals(ChatRequest.Search(sam, SearchType.TEXT), demo.requests.requests.value)
    }

    @Test
    fun deletingAsksFirstThenLeaves() {
        showScreen()
        scrollTo("Delete chat")
        compose.onNodeWithText("Delete chat").performClick()
        waitFor {
            compose
                .onAllNodesWithText(
                    "keeps its own copy",
                    substring = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onAllNodesWithText("Delete chat")[1].performClick()
        waitFor { left == 1 }
        assertNull(chat())
    }

    @Test
    fun theChatUsesItsOwnReactionSet() {
        runBlocking { demo.overrides.update(sam) { it.copy(quickReactions = listOf("🎉", "🙏")) } }
        val chat = demo.chatViewModel("sam")
        compose.setContent {
            PingMeTheme {
                androidx.compose.material3.Text(
                    chat.state
                        .collectAsStateWithLifecycle()
                        .value.title,
                )
            }
        }
        waitFor { chat.state.value.reactions.quick == listOf("🎉", "🙏") }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        val STEP: Duration = Duration.ofMillis(50)
    }
}
