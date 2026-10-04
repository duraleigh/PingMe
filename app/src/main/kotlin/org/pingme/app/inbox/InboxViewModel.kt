// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.pingme.app.R
import org.pingme.core.model.Account
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.NetworkId
import org.pingme.core.service.ChatActions
import org.pingme.core.service.IncomingReaction
import org.pingme.core.service.ReactionFeed
import org.pingme.core.service.TypingTracker
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.ui.theme.SwipeAction
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The inbox (UI_DESIGN.md 3.1, BUILD_PLAN.md P2.3). */
@HiltViewModel
class InboxViewModel
    @Inject
    constructor(
        private val chats: ChatRepository,
        messages: MessageRepository,
        private val accounts: AccountRepository,
        typing: TypingTracker,
        reactionFeed: ReactionFeed,
        private val actions: ChatActions,
        private val barRepository: InboxBarRepository,
        private val clock: Clock,
        private val saved: SavedStateHandle,
        private val settings: org.pingme.core.store.SettingsRepository,
        private val appearance: org.pingme.app.appearance.AppearanceRepository,
        contacts: org.pingme.core.store.ContactRepository,
        merges: org.pingme.core.service.merge.Merges,
        memberships: org.pingme.core.store.MergeRepository,
        suggestions: org.pingme.core.service.merge.MergeSuggestions,
    ) : ViewModel() {
        private val rowActions = RowActions(viewModelScope, actions, merges)

        /** The one-time "turn off Google Messages' notifications" prompt is due (UI_DESIGN.md 6.3). */
        val gmessagesReminder: StateFlow<Boolean> =
            combine(settings.app, accounts.accounts()) { app, all ->
                !app.gmessagesNotificationsReminderShown && all.any { it.network == NetworkId.GMESSAGES }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), false)

        fun dismissGmessagesReminder() {
            viewModelScope.launch { settings.updateApp { it.copy(gmessagesNotificationsReminderShown = true) } }
        }

        private val selected = saved.getStateFlow<String?>(SELECTED, null).map { it?.let(::decodeItem) }

        private val source =
            combine(
                chats.inbox(),
                chats.lowPriority(),
                messages.lastMessages(),
                accounts.accounts(),
                combine(
                    typing.typing,
                    chats.spaces(),
                    rowActions.pendingDeletes,
                    contacts.inChats(),
                    memberships.memberships(),
                    ::Extras,
                ),
            ) { inbox, low, last, accountList, extras ->
                InboxSource(
                    inbox,
                    low,
                    last,
                    accountList,
                    extras.typing.filterValues { it.isNotEmpty() }.keys,
                    extras.spaces,
                    extras.hidden,
                    extras.people,
                    extras.memberships,
                )
            }

        private val menu =
            combine(
                chats.archived(),
                chats.lowPriority(),
                chats.folder(ChatFolder.REQUESTS),
                chats.folder(ChatFolder.GENERAL),
                combine(chats.spaces(), chats.unreadTotals(), accounts.accounts(), suggestions.suggestions, ::Quad),
            ) { archived, low, requests, general, rest ->
                MenuCounts(
                    archived = archived.size,
                    lowPriority = low.size,
                    requests = requests.size,
                    general = general.size,
                    hasFolders = rest.c.any { it.network == NetworkId.INSTAGRAM },
                    spaces = rest.a.map { it to (rest.b.bySpace[it.id] ?: 0) },
                    suggestions = rest.d.size,
                )
            }

        val state: StateFlow<InboxUiState> =
            combine(
                source,
                barRepository.config,
                selected,
                chats.unreadTotals(),
                menu,
            ) { src, saved, pick, totals, counts ->
                val config = saved ?: defaultBar(src.accounts)
                val now = clock.now()
                // Everything the bar could hold; what it does not sits behind More (owner, 2026-10-03).
                val options =
                    listOf<InboxBarItem?>(null, InboxBarItem.Unread) +
                        src.accounts
                            .map { it.network }
                            .distinct()
                            .map { InboxBarItem.Network(it) } +
                        src.spaces.map { InboxBarItem.Space(it.id) } +
                        InboxBarItem.LowPriority
                val rest = options.filter { it !in config.buttons }
                // With All removed, the inbox opens on the first button (UI_DESIGN.md 10.4).
                val first = config.items.firstOrNull()?.takeIf { !config.showAll }
                val current = pick?.takeIf { it in config.items || it in rest } ?: first
                val (pinned, rows) = src.select(current, config.narrowings, now)
                val entryOf = { item: InboxBarItem? ->
                    if (item == null) BarEntry(null, src.allBadge(totals, now)) else entry(item, config, src, totals)
                }
                InboxUiState(
                    loading = false,
                    pinned = pinned,
                    rows = rows,
                    bar = config.buttons.map(entryOf),
                    more = rest.map(entryOf),
                    selected = current,
                    accounts = src.accounts,
                    menu = counts,
                    now = now,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), InboxUiState())

        /** Reactions to flip rows for, while Flippy reactions is on (UI_DESIGN.md 10.8). */
        val reactions =
            reactionFeed.reactions.filter {
                org.pingme.app.settings.flippyOn(
                    settings.app.first().flippyReactions,
                    appearance.appearance.first().motion,
                )
            }

        val messages = rowActions.messages

        fun select(item: InboxBarItem?) {
            saved[SELECTED] = item?.let { JSON.encodeToString(InboxBarItem.serializer(), it) }
        }

        /** A long-press choice on a network button; null shows every account and folder again. */
        fun narrow(
            network: NetworkId,
            narrowing: Narrowing?,
        ) = editBar { config ->
            config.copy(
                narrowings =
                    if (narrowing ==
                        null
                    ) {
                        config.narrowings - network
                    } else {
                        config.narrowings + (network to narrowing)
                    },
            )
        }

        /** The bar's buttons from the editor, null standing for All. */
        fun setBarItems(buttons: List<InboxBarItem?>) = editBar { it.withButtons(buttons) }

        fun swipe(
            row: ChatRow,
            action: SwipeAction,
        ) = rowActions.swipe(row, action)

        fun perform(
            row: ChatRow,
            action: ChatAction,
        ) = rowActions.perform(row, action)

        fun bulk(
            rows: List<ChatRow>,
            action: BulkAction,
        ) = rowActions.bulk(rows, action)

        /** The demo network's status pill cycles Connected, reconnecting, needs attention (BUILD_PLAN.md P2.3). */
        fun cycleDemoState() {
            viewModelScope.launch {
                val demo = accounts.getAll().firstOrNull { it.network == NetworkId.DEMO } ?: return@launch
                val next =
                    when (demo.state) {
                        ConnectionState.Connected -> {
                            ConnectionState.Reconnecting(1, clock.now() + DEMO_RETRY)
                        }

                        is ConnectionState.Reconnecting -> {
                            ConnectionState.ActionNeeded(
                                "Sign in to the demo network again",
                                null,
                            )
                        }

                        else -> {
                            ConnectionState.Connected
                        }
                    }
                accounts.updateState(demo.id, next)
            }
        }

        private fun editBar(change: (InboxBarConfig) -> InboxBarConfig) {
            viewModelScope.launch {
                barRepository.update(defaultBar(accounts.getAll()), change)
            }
        }

        private fun entry(
            item: InboxBarItem,
            config: InboxBarConfig,
            src: InboxSource,
            totals: org.pingme.core.store.UnreadTotals,
        ) = when (item) {
            is InboxBarItem.Network -> {
                BarEntry(
                    item,
                    badgeFor(item, totals),
                    narrowOptions = narrowOptions(item.network, src.accounts),
                    narrowedTo = config.narrowings[item.network],
                )
            }

            is InboxBarItem.Space -> {
                val space = src.spaces.find { it.id == item.id }
                BarEntry(item, badgeFor(item, totals), spaceName = space?.title, spaceIcon = space?.icon)
            }

            else -> {
                BarEntry(item, badgeFor(item, totals))
            }
        }

        private fun defaultBar(accountList: List<Account>) = InboxBarConfig.default(accountList.map { it.network })

        private fun decodeItem(json: String): InboxBarItem? =
            runCatching {
                JSON.decodeFromString(InboxBarItem.serializer(), json)
            }.getOrNull()

        private data class Extras(
            val typing: Map<ChatId, Set<org.pingme.core.model.PersonId>>,
            val spaces: List<org.pingme.core.model.Space>,
            val hidden: Set<ChatId>,
            val people: Map<org.pingme.core.model.PersonId, org.pingme.core.model.Person>,
            val memberships: Map<ChatId, ChatId>,
        )

        private data class Quad<A, B, C, D>(
            val a: A,
            val b: B,
            val c: C,
            val d: D,
        )

        private companion object {
            const val SELECTED = "selected"
            const val STOP_AFTER = 5_000L
            val DEMO_RETRY = 30.seconds
            val JSON = Json { ignoreUnknownKeys = true }
        }
    }
