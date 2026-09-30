// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationMode
import org.pingme.core.model.Space
import org.pingme.core.model.SpaceId
import org.pingme.core.model.SpaceKind
import org.pingme.core.store.UnreadTotals
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The inbox's filtering, badges, and bar defaults (UI_DESIGN.md 3.1, 6.4, 6.5, 10.4, 10.7). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class InboxStateTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val rcs = account("rcs", NetworkId.GMESSAGES)
    private val ig = account("ig", NetworkId.INSTAGRAM)
    private val page = account("page", NetworkId.MESSENGER)
    private val personal = account("fb", NetworkId.MESSENGER)

    private fun account(
        id: String,
        network: NetworkId,
    ) = Account(AccountId(id), network, id, 0, ConnectionState.Connected, true, NotificationMode.NORMAL, id)

    private fun chat(
        id: String,
        account: Account = rcs,
        unread: Int = 0,
        pin: Int? = null,
        muted: Boolean = false,
        folder: ChatFolder? = null,
        space: SpaceId? = null,
    ) = Chat(
        ChatId(id),
        account.id,
        ChatKind.DIRECT,
        id,
        emptyList(),
        unread,
        now,
        pin != null,
        pin,
        muted,
        null,
        false,
        false,
        false,
        folder,
        space,
        null,
        AvatarSource.Initials,
        null,
        null,
        id,
    )

    private fun source(vararg chats: Chat) =
        InboxSource(chats.toList(), emptyList(), emptyMap(), listOf(rcs, ig, page, personal), emptySet(), emptyList())

    private fun Pair<List<ChatRow>, List<ChatRow>>.ids() = first.map { it.id.value } to second.map { it.id.value }

    @Test
    fun pinnedChatsLeaveTheListAndKeepTheirOrder() {
        val src = source(chat("a"), chat("b", pin = 1), chat("c", pin = 0), chat("d"))
        assertEquals(listOf("c", "b") to listOf("a", "d"), src.select(null, emptyMap(), now).ids())
    }

    @Test
    fun unreadLeavesOutReadAndMutedChats() {
        val src = source(chat("read"), chat("new", unread = 2), chat("quiet", unread = 5, muted = true))
        assertEquals(emptyList<String>() to listOf("new"), src.select(InboxBarItem.Unread, emptyMap(), now).ids())
    }

    @Test
    fun aNetworkCoversAllItsAccountsUntilNarrowed() {
        val src = source(chat("mine", personal), chat("biz", page), chat("text", rcs))
        val messenger = InboxBarItem.Network(NetworkId.MESSENGER)
        assertEquals(listOf("mine", "biz"), src.select(messenger, emptyMap(), now).second.map { it.id.value })
        val onlyPage = mapOf(NetworkId.MESSENGER to Narrowing.Account(page.id))
        assertEquals(listOf("biz"), src.select(messenger, onlyPage, now).second.map { it.id.value })
    }

    @Test
    fun instagramNarrowsToPrimaryOrGeneral() {
        val src =
            source(
                chat("p", ig, folder = ChatFolder.PRIMARY),
                chat("g", ig, folder = ChatFolder.GENERAL),
                chat("unsorted", ig),
            )
        val instagram = InboxBarItem.Network(NetworkId.INSTAGRAM)
        val general = mapOf(NetworkId.INSTAGRAM to Narrowing.Folder(ChatFolder.GENERAL))
        val primary = mapOf(NetworkId.INSTAGRAM to Narrowing.Folder(ChatFolder.PRIMARY))
        assertEquals(listOf("g"), src.select(instagram, general, now).second.map { it.id.value })
        assertEquals(listOf("p", "unsorted"), src.select(instagram, primary, now).second.map { it.id.value })
    }

    @Test
    fun aSpaceHoldsItsOwnChatsAndTheOnesAddedByHand() {
        val id = SpaceId("s")
        val src =
            source(chat("topic", space = id), chat("added"), chat("other"))
                .copy(spaces = listOf(Space(id, null, "Family", SpaceKind.CUSTOM, listOf(ChatId("added")))))
        assertEquals(
            listOf("topic", "added"),
            src.select(InboxBarItem.Space(id), emptyMap(), now).second.map { it.id.value },
        )
    }

    @Test
    fun deletedChatsWaitingOnUndoAreHidden() {
        val src = source(chat("a"), chat("b")).copy(hidden = setOf(ChatId("a")))
        assertEquals(listOf("b"), src.select(null, emptyMap(), now).second.map { it.id.value })
    }

    @Test
    fun badgesFollowTheOneCountingRule() {
        val totals = UnreadTotals(7, emptyMap(), mapOf(NetworkId.WHATSAPP to 3), mapOf(SpaceId("s") to 2))
        assertEquals(7, badgeFor(null, totals))
        assertEquals(7, badgeFor(InboxBarItem.Unread, totals))
        assertEquals(3, badgeFor(InboxBarItem.Network(NetworkId.WHATSAPP), totals))
        assertEquals(0, badgeFor(InboxBarItem.Network(NetworkId.SIGNAL), totals))
        assertEquals(2, badgeFor(InboxBarItem.Space(SpaceId("s")), totals))
        assertEquals("low priority never counts", 0, badgeFor(InboxBarItem.LowPriority, totals))
    }

    @Test
    fun longPressOffersAccountsWhenThereAreSeveralAndInstagramFolders() {
        val all = listOf(rcs, ig, page, personal)
        assertEquals(emptyList<Narrowing>(), narrowOptions(NetworkId.GMESSAGES, all))
        assertEquals(
            listOf(Narrowing.Account(page.id), Narrowing.Account(personal.id)),
            narrowOptions(NetworkId.MESSENGER, all),
        )
        assertEquals(
            listOf(Narrowing.Folder(ChatFolder.PRIMARY), Narrowing.Folder(ChatFolder.GENERAL)),
            narrowOptions(NetworkId.INSTAGRAM, all),
        )
    }

    @Test
    fun theDefaultBarIsUnreadThenYourNetworksUpToFiveButtons() {
        val bar =
            InboxBarConfig.default(
                listOf(
                    NetworkId.GMESSAGES,
                    NetworkId.WHATSAPP,
                    NetworkId.GMESSAGES,
                    NetworkId.TELEGRAM,
                    NetworkId.SIGNAL,
                ),
            )
        assertEquals(
            listOf(
                InboxBarItem.Unread,
                InboxBarItem.Network(NetworkId.GMESSAGES),
                InboxBarItem.Network(NetworkId.WHATSAPP),
                InboxBarItem.Network(NetworkId.TELEGRAM),
            ),
            bar.items,
        )
    }

    @Test
    fun timesReadNowThenClockThenWeekdayThenDate() {
        val utc = ZoneOffset.UTC

        fun label(ago: kotlin.time.Duration) = timeLabel(now - ago, now, "now", utc, Locale.US)
        assertEquals("now", label(30.seconds))
        assertEquals("9:00 AM", label(3.hours))
        assertEquals("Mon", label(2.days))
        assertEquals("Sep 10", label(20.days))
        assertEquals("9/30/25", label(365.days))
    }
}
