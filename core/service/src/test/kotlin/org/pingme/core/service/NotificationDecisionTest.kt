// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ChatSnapshot
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatOverrides
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.KeywordRuleId
import org.pingme.core.model.KeywordScope
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.NotificationSettings
import kotlin.time.Duration.Companion.minutes

class NotificationDecisionTest : ServiceTest() {
    private val notify = NotificationDecision.Notify(NotificationRouter.DEFAULT_CHANNEL)

    private fun chat() =
        chatSnapshot().let { s: ChatSnapshot ->
            org.pingme.core.model.Chat(
                s.id,
                s.accountId,
                s.kind,
                s.title,
                emptyList(),
                0,
                now,
                false,
                null,
                false,
                null,
                false,
                false,
                false,
                null,
                null,
                null,
                org.pingme.core.model.AvatarSource.Contacts,
                null,
                null,
                "c1",
            )
        }

    @Test
    fun incomingMessagesNotifyUnlessTheChatIsSilent() {
        val incoming = messageSnapshot("m").message
        assertEquals(notify, NotificationRouter.decide(chat(), incoming, now))
        assertEquals(
            NotificationDecision.Drop,
            NotificationRouter.decide(chat(), messageSnapshot("o", outgoing = true).message, now),
        )
        assertEquals(NotificationDecision.Drop, NotificationRouter.decide(chat().copy(isMuted = true), incoming, now))
        assertEquals(
            NotificationDecision.Drop,
            NotificationRouter.decide(chat().copy(isLowPriority = true), incoming, now),
        )
        val mutedUntilLater = chat().copy(isMuted = true, muteUntil = now + 1.minutes)
        assertEquals(NotificationDecision.Drop, NotificationRouter.decide(mutedUntilLater, incoming, now))
        val muteOver = chat().copy(isMuted = true, muteUntil = now - 1.minutes)
        assertEquals(notify, NotificationRouter.decide(muteOver, incoming, now))
    }

    @Test
    fun channelsFollowKeywordThenChatThenFolderThenAccountThenDefault() {
        val incoming = messageSnapshot("m", body = "lunch tomorrow?").message
        val rule = KeywordRule(KeywordRuleId("k1"), "lunch", true, false, KeywordScope.All, "keyword_k1", false)
        val own = ChatOverrides(chat().id, soundUri = "content://sound/1", channelVersion = 2)
        val folderChat = chat().copy(folder = ChatFolder.GENERAL)
        val quietAccount = account().copy(notificationMode = NotificationMode.SILENT)
        assertEquals("keyword_k1_0", decide(incoming, keywords = listOf(rule), overrides = own).channel())
        assertEquals("chat_${chat().id.value}_2", decide(incoming, overrides = own, chat = folderChat).channel())
        assertEquals("folder_GENERAL_0", decide(incoming, chat = folderChat, account = account()).channel())
        assertEquals("account_acc_0", decide(incoming, account = quietAccount).channel())
        assertTrue((decide(incoming, account = quietAccount) as NotificationDecision.Notify).silent)
        assertEquals(NotificationRouter.DEFAULT_CHANNEL, decide(incoming, account = account()).channel())
        // The chat on screen never notifies; a keyword that does not override still respects mute.
        assertEquals(NotificationDecision.Drop, decide(incoming, visible = chat().id))
        assertEquals(
            NotificationDecision.Drop,
            decide(incoming, chat = chat().copy(isMuted = true), keywords = listOf(rule)),
        )
    }

    @Test
    fun keywordsMatchWholeWordsAndTheirScope() {
        val rule =
            KeywordRule(
                KeywordRuleId("k"),
                "cat",
                wholeWord = true,
                caseSensitive = false,
                KeywordScope.All,
                "c",
                false,
            )
        assertTrue(rule.matches("my CAT is here", chat()))
        assertFalse(rule.matches("concatenate", chat()))
        assertTrue(rule.copy(wholeWord = false).matches("concatenate", chat()))
        assertFalse(rule.copy(caseSensitive = true).matches("my CAT", chat()))
        assertFalse(rule.copy(scope = KeywordScope.Chats(listOf(ChatId("other")))).matches("cat", chat()))
        assertTrue(rule.copy(scope = KeywordScope.Accounts(listOf(accountId))).matches("cat", chat()))
        assertFalse(rule.matches(null, chat()))
    }

    private fun decide(
        message: org.pingme.core.model.Message,
        chat: org.pingme.core.model.Chat = chat(),
        keywords: List<KeywordRule> = emptyList(),
        overrides: ChatOverrides? = null,
        account: org.pingme.core.model.Account? = null,
        visible: ChatId? = null,
    ) = NotificationRouter.decide(chat, message, now, keywords, NotificationSettings(), overrides, account, visible)

    private fun NotificationDecision.channel() = (this as NotificationDecision.Notify).channelId
}
