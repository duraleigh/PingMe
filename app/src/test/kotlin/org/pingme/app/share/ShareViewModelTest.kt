// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.share

import android.content.Intent
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.connector.chat
import org.pingme.core.model.ChatId
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * Sharing into PingMe sends what was shared, and says so when it cannot (owner,
 * 2026-10-06: "Send gets pressed. But nothing gets sent as a message").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private val sam get() = demo.account.id.chat("sam")
    private val watching = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val finished = mutableListOf<ChatId?>()
    private val failed = mutableListOf<String?>()

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(liveActivity = false) }
        runBlocking { demo.seed() }
    }

    @After
    fun tearDown() {
        watching.cancel()
        demo.close()
    }

    // The view model works on the main thread, which a test must keep turning while it waits.
    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT
        while (!condition()) {
            if (System.currentTimeMillis() > until) fail("Timed out waiting")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
            Thread.sleep(STEP_MS)
        }
    }

    private fun share(intent: Intent): ShareViewModel {
        val vm = demo.shareViewModel(intent)
        // The state only runs while something watches it, as the screen would.
        watching.launch { vm.state.collect {} }
        watching.launch { vm.finished.collect { finished += it } }
        watching.launch { vm.failed.collect { failed += it } }
        waitFor {
            vm.state.value.chats
                .isNotEmpty()
        }
        vm.toggle(
            vm.state.value.chats
                .first { it.key == sam.value },
        )
        return vm
    }

    @Test
    fun sharedTextIsSentToThePickedChatAndThePickerOpensIt() {
        val vm = share(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://example.com/story"))
        vm.send()
        waitFor { finished.isNotEmpty() }
        assertEquals(listOf<ChatId?>(sam), finished)
        val mine = runBlocking { demo.messages.latest(sam, 5).first() }.filter { it.isOutgoing }
        assertEquals("https://example.com/story", mine.first().body)
    }

    @Test
    fun aFileThatCannotBeReadSaysSoAndThePickerStays() {
        val unreadable = Uri.parse("content://com.example.nothing/missing")
        val vm = share(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, unreadable).setType("image/jpeg"))
        vm.send()
        waitFor { failed.isNotEmpty() }
        assertEquals(listOf<String?>("the shared file could not be read"), failed)
        waitFor { !vm.state.value.sending }
        // Nothing went, so the picker does not close as if it had.
        assertTrue(finished.isEmpty())
        assertTrue(
            vm.state.value.picked
                .contains(sam.value),
        )
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val STEP_MS = 20L
    }
}
