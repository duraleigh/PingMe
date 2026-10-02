// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.KeywordRuleId
import org.pingme.core.model.KeywordScope
import org.pingme.core.model.MessageKind
import org.pingme.core.model.NotificationMode
import org.pingme.core.service.notify.MessageNotifications
import org.pingme.core.service.notify.NotificationTaps
import org.robolectric.Shadows.shadowOf

/** Posting, clearing, and what counts as new (BUILD_PLAN.md P4.1; owner, Gate G2). */
class NotificationRouterTest : ServiceTest() {
    private val manager
        get() =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(NotificationManager::class.java)

    private val chatId: ChatId get() = accountId.chat("c1")

    private fun posted() =
        shadowOf(manager).allNotifications.filter {
            it.group == "org.pingme.messages" &&
                !it.isGroupSummary()
        }

    private fun android.app.Notification.isGroupSummary() = flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0

    private fun textOf(notification: android.app.Notification) =
        notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()

    private suspend fun seed() {
        accounts.upsert(account())
        applier.applyChats(listOf(chatSnapshot()))
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun arrive(event: ConnectorEvent.NewMessage) {
        val fresh = router.isFresh(event)
        applier.apply(event)
        router.onEvent(event, fresh)
    }

    @Test
    fun aNewMessagePostsOnceAndARepeatOfItDoesNot() =
        runBlocking {
            seed()
            val event = ConnectorEvent.NewMessage(accountId, messageSnapshot("m1", body = "On my way"))
            arrive(event)
            assertEquals(1, posted().size)
            val shown = textOf(posted().single())
            // The same message again (a reaction or a status change re-sends it) stays quiet.
            manager.cancelAll()
            arrive(event)
            assertTrue("a known message does not notify again", posted().isEmpty())
            assertEquals("On my way", shown)
        }

    @Test
    fun theNotificationOpensItsChatAndOffersReplyAndMarkRead() =
        runBlocking {
            seed()
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1")))
            val notification = posted().single()
            val intent = shadowOf(notification.contentIntent).savedIntent
            assertEquals(chatId, NotificationTaps.chatIn(intent))
            assertEquals(listOf("Reply", "Mark read"), notification.actions.map { it.title.toString() })
        }

    @Test
    fun readingTheChatTakesTheNotificationDown() =
        runBlocking {
            seed()
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1")))
            assertEquals(1, posted().size)
            router.clear(chatId)
            assertTrue(posted().isEmpty())
        }

    @Test
    fun aChatReadOnThePhoneTakesItsNotificationDown() =
        runBlocking {
            seed()
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1")))
            assertEquals(1, posted().size)
            val read = chatSnapshot().copy(unreadCount = 0)
            applier.apply(ConnectorEvent.ChatUpdated(accountId, read))
            router.onEvent(ConnectorEvent.ChatUpdated(accountId, read), fresh = false)
            assertTrue(posted().isEmpty())
            assertTrue("the group line goes with it", shadowOf(manager).allNotifications.isEmpty())
        }

    @Test
    fun withPingMeOpenNothingLandsInTheShade() =
        runBlocking {
            seed()
            presence.appVisible = true
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1")))
            assertTrue(posted().isEmpty())
            presence.appVisible = false
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m2")))
            assertEquals(1, posted().size)
        }

    @Test
    fun theChatOnScreenDoesNotNotify() =
        runBlocking {
            seed()
            presence.visible = chatId
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1")))
            assertTrue(posted().isEmpty())
            presence.visible = null
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m2")))
            assertEquals(1, posted().size)
        }

    @Test
    fun aPictureReadsAsSentAPicture() =
        runBlocking {
            seed()
            val picture =
                messageSnapshot(
                    "m1",
                    body = "",
                ).let { it.copy(message = it.message.copy(kind = MessageKind.IMAGE)) }
            arrive(ConnectorEvent.NewMessage(accountId, picture))
            assertEquals("sent a picture", textOf(posted().single()))
        }

    @Test
    fun aKeywordNamesItselfAndBeatsMute() =
        runBlocking {
            seed()
            chats.update(chatId) { it.copy(isMuted = true) }
            settings.upsertKeywordRule(
                KeywordRule(KeywordRuleId("k1"), "urgent", true, false, KeywordScope.All, "keyword_k1", true),
            )
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1", body = "This is URGENT")))
            val notification = posted().single()
            assertTrue(notification.channelId.startsWith("keyword_k1"))
            assertEquals("Keyword: urgent", notification.extras.getString(android.app.Notification.EXTRA_SUB_TEXT))
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m2", body = "nothing special")))
            assertEquals("the muted chat stays quiet without the keyword", 1, posted().size)
        }

    @Test
    fun anAccountSetToOffNeverNotifies() =
        runBlocking {
            seed()
            accounts.upsert(account().copy(notificationMode = NotificationMode.OFF))
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m1")))
            assertTrue(posted().isEmpty())
        }

    @Test
    fun aOneTimeCodeGetsACopyButtonAndAutoCopyPutsItOnTheClipboard() =
        runBlocking {
            seed()
            arrive(
                ConnectorEvent.NewMessage(accountId, messageSnapshot("m1", body = "Your verification code is 482913")),
            )
            assertEquals(
                listOf("Reply", "Mark read", "Copy code"),
                posted().single().actions.map { it.title.toString() },
            )
            val clipboard =
                ApplicationProvider.getApplicationContext<Context>().getSystemService(
                    android.content.ClipboardManager::class.java,
                )
            assertTrue("nothing copied until auto-copy is on", clipboard.primaryClip == null)
            settings.updateApp { it.copy(notifications = it.notifications.copy(autoCopyCodes = true)) }
            arrive(ConnectorEvent.NewMessage(accountId, messageSnapshot("m2", body = "G-555123 is your Google code")))
            assertEquals(
                "555123",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }

    @Test
    fun freshMeansIncomingAndUnknown() =
        runBlocking {
            seed()
            val incoming = ConnectorEvent.NewMessage(accountId, messageSnapshot("m1"))
            assertTrue(router.isFresh(incoming))
            applier.apply(incoming)
            assertFalse(router.isFresh(incoming))
            assertFalse(router.isFresh(ConnectorEvent.NewMessage(accountId, messageSnapshot("m2", outgoing = true))))
            assertEquals(MessageNotifications.MESSAGE_ID, 100)
        }
}
