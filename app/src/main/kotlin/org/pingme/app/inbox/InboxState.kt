// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import org.pingme.core.connector.accountId
import org.pingme.core.model.Account
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Space
import org.pingme.core.store.LastMessage
import org.pingme.core.store.UnreadTotals
import kotlin.time.Instant

/** One chat as the inbox shows it (UI_DESIGN.md 3.1). */
data class ChatRow(
    val chat: Chat,
    val network: NetworkId,
    val last: LastMessage?,
    val typing: Boolean,
    /** The contact's or network's photo for the row's avatar; null for the initials tile. */
    val photo: String? = null,
    /** A merged chat's networks, for its badges; one network otherwise (UI_DESIGN.md 10.15). */
    val networks: List<NetworkId> = listOf(network),
) {
    val id: ChatId get() = chat.id
    val title: String get() = chat.nameOverride ?: chat.title
    val isUnread: Boolean get() = chat.unreadCount > 0
}

/** A bar button with its unread badge. [item] null is All. */
data class BarEntry(
    val item: InboxBarItem?,
    val badge: Int,
    /** For a space button: its name and icon. */
    val spaceName: String? = null,
    val spaceIcon: org.pingme.core.model.SpaceIcon? = null,
    /** For a network button: accounts or folders a long-press can narrow to. */
    val narrowOptions: List<Narrowing> = emptyList(),
    val narrowedTo: Narrowing? = null,
)

/** The avatar menu's lists and their counts (UI_DESIGN.md 3.1, 6.4, 10.4, 10.7). */
data class MenuCounts(
    val archived: Int = 0,
    val lowPriority: Int = 0,
    val requests: Int = 0,
    val general: Int = 0,
    /** Instagram is connected, so Requests and General have somewhere to come from. */
    val hasFolders: Boolean = false,
    val spaces: List<Pair<Space, Int>> = emptyList(),
    /** Merge suggestions waiting for the user (owner, Phase 7). */
    val suggestions: Int = 0,
)

data class InboxUiState(
    val loading: Boolean = true,
    val pinned: List<ChatRow> = emptyList(),
    val rows: List<ChatRow> = emptyList(),
    val bar: List<BarEntry> = listOf(BarEntry(null, 0)),
    /** Everything the bar does not hold, behind the fixed More button (owner, 2026-10-03). */
    val more: List<BarEntry> = emptyList(),
    val selected: InboxBarItem? = null,
    val accounts: List<Account> = emptyList(),
    val menu: MenuCounts = MenuCounts(),
    val now: Instant = Instant.DISTANT_PAST,
) {
    /** Accounts that are not Connected and want attention, worst first (UI_DESIGN.md 3.1). */
    val problems: List<Account>
        get() =
            accounts
                .filter { it.state is ConnectionState.ActionNeeded || it.state is ConnectionState.Reconnecting }
                .sortedBy { if (it.state is ConnectionState.ActionNeeded) 0 else 1 }
}

/** Everything the inbox is built from, gathered in one place so the filtering is a pure function. */
data class InboxSource(
    val inbox: List<Chat>,
    val lowPriority: List<Chat>,
    val last: Map<ChatId, LastMessage>,
    val accounts: List<Account>,
    val typing: Set<ChatId>,
    val spaces: List<Space>,
    val hidden: Set<ChatId> = emptySet(),
    /** Everyone some chat lists, for names and photos (UI_DESIGN.md 10.18). */
    val people: Map<org.pingme.core.model.PersonId, org.pingme.core.model.Person> = emptyMap(),
    /** Each member chat and the merged chat it belongs to (UI_DESIGN.md 10.15). */
    val memberships: Map<ChatId, ChatId> = emptyMap(),
)

/** Pinned chats and the list for one bar selection. Pinned chats never repeat in the list. */
fun InboxSource.select(
    selected: InboxBarItem?,
    narrowings: Map<NetworkId, Narrowing>,
    now: Instant,
): Pair<List<ChatRow>, List<ChatRow>> {
    val networkOf = accounts.associate { it.id to it.network }
    val chats =
        when (selected) {
            // A space can keep its chats out of All, inside the space only (UI_DESIGN.md 10.4).
            null -> {
                val onlyInSpaces = onlyInSpaces()
                inbox.filter { it.id !in onlyInSpaces }
            }

            InboxBarItem.Unread -> {
                inbox.filter { it.unreadCount > 0 && !it.mutedAt(now) }
            }

            is InboxBarItem.Network -> {
                // A merged chat shows under every network it has a member on.
                inbox.filter {
                    (networkOf[it.accountId] == selected.network || selected.network in networksOf(it.id, networkOf)) &&
                        it.matches(narrowings[selected.network])
                }
            }

            is InboxBarItem.Space -> {
                val members =
                    spaces
                        .find { it.id == selected.id }
                        ?.chatIds
                        .orEmpty()
                        .toSet()
                inbox.filter { it.spaceId == selected.id || it.id in members }
            }

            InboxBarItem.LowPriority -> {
                lowPriority
            }
        }.filter { it.id !in hidden }
    val rows =
        chats.map {
            val network = networkOf[it.accountId] ?: NetworkId.DEMO
            val members = memberships.filterValues { parent -> parent == it.id }.keys
            ChatRow(
                it,
                network,
                // A merged chat previews the newest message across its members.
                if (members.isEmpty()) {
                    last[it.id]
                } else {
                    members
                        .mapNotNull { m ->
                            last[m]
                        }.maxByOrNull { l -> l.sentAt }
                },
                it.id in typing || members.any { m -> m in typing },
                photoFor(it, people),
                networksOf(it.id, networkOf).ifEmpty { listOf(network) },
            )
        }
    val (pinned, rest) = rows.partition { it.chat.isPinned }
    return pinned.sortedBy { it.chat.pinOrder ?: Int.MAX_VALUE } to rest
}

/** The networks a merged chat's members are on, in member order; empty for an ordinary chat. */
private fun InboxSource.networksOf(
    parent: ChatId,
    networkOf: Map<org.pingme.core.model.AccountId, NetworkId>,
): List<NetworkId> =
    memberships
        .filterValues { it == parent }
        .keys
        .mapNotNull { networkOf[it.accountId] }
        .distinct()

/** Chats in a space that keeps them out of All. */
fun InboxSource.onlyInSpaces(): Set<ChatId> {
    val inside = spaces.filter { !it.showInAll }
    if (inside.isEmpty()) return emptySet()
    val ids = inside.map { it.id }.toSet()
    return inside.flatMap { it.chatIds }.toSet() + inbox.filter { it.spaceId in ids }.map { it.id }
}

/** All's badge: the one counting rule's total, less the unread chats All does not show. */
fun InboxSource.allBadge(
    totals: UnreadTotals,
    now: Instant,
): Int {
    val outside = onlyInSpaces()
    val left = inbox.filter { it.id in outside && !it.mutedAt(now) }.sumOf { it.unreadCount }
    return (totals.total - left).coerceAtLeast(0)
}

/** Unread badges for the bar, from the one counting rule (UI_DESIGN.md 6.4). */
fun badgeFor(
    item: InboxBarItem?,
    totals: UnreadTotals,
) = when (item) {
    null, InboxBarItem.Unread -> totals.total

    is InboxBarItem.Network -> totals.byNetwork[item.network] ?: 0

    is InboxBarItem.Space -> totals.bySpace[item.id] ?: 0

    // Low priority never counts toward an unread total (UI_DESIGN.md 10.7).
    InboxBarItem.LowPriority -> 0
}

/** What a long-press on [network] can narrow to: each account when there are several, and Instagram's folders. */
fun narrowOptions(
    network: NetworkId,
    accounts: List<Account>,
): List<Narrowing> {
    val mine = accounts.filter { it.network == network }
    val byAccount = if (mine.size > 1) mine.map { Narrowing.Account(it.id) } else emptyList()
    val folders =
        if (network == NetworkId.INSTAGRAM) {
            listOf(Narrowing.Folder(ChatFolder.PRIMARY), Narrowing.Folder(ChatFolder.GENERAL))
        } else {
            emptyList()
        }
    return byAccount + folders
}

private fun Chat.matches(narrowing: Narrowing?) =
    when (narrowing) {
        null -> true

        is Narrowing.Account -> accountId == narrowing.id

        // Chats Instagram has not sorted yet show as Primary.
        is Narrowing.Folder -> (folder ?: ChatFolder.PRIMARY) == narrowing.folder
    }

/** Muted now: a timed mute stops counting once it ends. */
fun Chat.mutedAt(now: Instant) = isMuted && muteUntil.let { it == null || it > now }
