// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatOverrides
import org.pingme.core.model.VibrationPattern

/** Each chat's own settings (UI_DESIGN.md 3.4, 6.1). */
class ChatOverridesStoreTest : StoreTest() {
    private val overrides by lazy { ChatOverridesRepository(db) }
    private val c1 = ChatId("c1")

    private suspend fun seed() {
        accounts.upsert(account("a"))
        chats.upsert(chat("c1", "a"))
    }

    @Test
    fun aChatWithNoneFollowsTheApp() =
        runTest {
            seed()
            assertEquals(ChatOverrides(c1), overrides.overrides(c1).first())
        }

    @Test
    fun eachNewSoundGetsANewChannel() =
        runTest {
            seed()
            overrides.setNotification(c1, "content://media/ring/1", VibrationPattern.DOUBLE)
            overrides.setNotification(c1, "content://media/ring/2", VibrationPattern.DOUBLE)
            val now = overrides.get(c1)
            assertEquals("content://media/ring/2" to 2, now.soundUri to now.channelVersion)
            assertEquals(VibrationPattern.DOUBLE, now.vibration)
        }

    @Test
    fun lookAndReactionsAreKeptAndGoWithTheChat() =
        runTest {
            seed()
            overrides.update(c1) { it.copy(lookJson = "{}", quickReactions = listOf("🎉", "🙏")) }
            assertEquals(listOf("🎉", "🙏"), overrides.get(c1).quickReactions)
            chats.delete(c1)
            assertEquals(ChatOverrides(c1), overrides.get(c1))
        }
}
