// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.connectors.telegram

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.model.AccountId

/** Telegram's own notes never flood the inbox and never read as "not supported" (owner, Gate G7). */
class TelegramNoticesTest {
    private val go = TelegramTranslate(AccountId("acct"))

    private fun privateChat(content: TdApi.MessageContent?) =
        TdApi.Chat().apply {
            id = 7
            type = TdApi.ChatTypePrivate(7)
            title = "Ann"
            positions = arrayOf(TdApi.ChatPosition(TdApi.ChatListMain(), 5L, false, null))
            lastMessage = content?.let { c -> TdApi.Message().apply { this.content = c } }
        }

    @Test
    fun aJoinedTelegramNoteAloneIsNotAChat() {
        val notice = privateChat(TdApi.MessageContactRegistered())
        assertTrue(go.inMainList(notice))
        assertTrue(go.noticeOnly(notice))
        assertFalse(go.listed(notice))
    }

    @Test
    fun aRealMessageMakesItAChat() {
        val text = privateChat(TdApi.MessageText(TdApi.FormattedText("hi", emptyArray()), null, null))
        assertFalse(go.noticeOnly(text))
        assertTrue(go.listed(text))
        assertTrue(go.listed(privateChat(null)))
    }

    @Test
    fun groupNotesAreNotHidden() {
        val group =
            TdApi.Chat().apply {
                id = 8
                type = TdApi.ChatTypeBasicGroup(8)
                positions = arrayOf(TdApi.ChatPosition(TdApi.ChatListMain(), 5L, false, null))
                lastMessage = TdApi.Message().apply { content = TdApi.MessageContactRegistered() }
            }
        assertTrue(go.listed(group))
    }

    @Test
    fun notesReadAsWords() {
        assertEquals("Joined Telegram", go.textOf(TdApi.MessageContactRegistered()))
        assertEquals("Pinned a message", go.textOf(TdApi.MessagePinMessage(3)))
        assertEquals("Changed the group name to Trip", go.textOf(TdApi.MessageChatChangeTitle("Trip")))
        assertEquals("", go.textOf(TdApi.MessageUnsupported()))
    }
}
