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
    ) : ViewModel() {
        private val rowActions = RowActions(viewModelScope, actions)
        private val selected = saved.getStateFlow<String?>(SELECTED, null).map { it?.let(::decodeItem) }

        private val source =
            combine(
                chats.inbox(),
                chats.lowPriority(),
                messages.lastMessages(),
                accounts.accounts(),
                combine(typing.typing, chats.spaces(), rowActions.pendingDeletes, ::Triple),
            ) { inbox, low, last, accountList, (typingNow, spaces, hidden) ->
                InboxSource(
                    inbox,
                    low,
                    last,
                    accountList,
                    typingNow.filterValues { it.isNotEmpty() }.keys,
                    spaces,
                    hidden,
                )
            }

        private val menu =
            combine(
                chats.archived(),
                chats.lowPriority(),
                chats.folder(ChatFolder.REQUESTS),
                chats.folder(ChatFolder.GENERAL),
                combine(chats.spaces(), chats.unreadTotals(), accounts.accounts(), ::Triple),
            ) { archived, low, requests, general, (spaces, totals, accountList) ->
                MenuCounts(
                    archived = archived.size,
                    lowPriority = low.size,
                    requests = requests.size,
                    general = general.size,
                    hasFolders = accountList.any { it.network == NetworkId.INSTAGRAM },
                    spaces = spaces.map { it to (totals.bySpace[it.id] ?: 0) },
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
                val current = pick?.takeIf { it in config.items }
                val (pinned, rows) = src.select(current, config.narrowings, now)
                InboxUiState(
                    loading = false,
                    pinned = pinned,
                    rows = rows,
                    bar =
                        listOf(BarEntry(null, totals.total)) +
                            config.items.map { item -> entry(item, config, src, totals) },
                    selected = current,
                    accounts = src.accounts,
                    menu = counts,
                    now = now,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), InboxUiState())

        /** Reactions to flip rows for (UI_DESIGN.md 10.8). */
        val reactions = reactionFeed.reactions

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

        fun setBarItems(items: List<InboxBarItem>) = editBar { it.copy(items = items) }

        fun swipe(
            row: ChatRow,
            action: SwipeAction,
        ) = rowActions.swipe(row, action)

        fun perform(
            row: ChatRow,
            action: ChatAction,
        ) = rowActions.perform(row, action)

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
                BarEntry(
                    item,
                    badgeFor(item, totals),
                    spaceName =
                        src.spaces
                            .find {
                                it.id ==
                                    item.id
                            }?.title,
                )
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

        private companion object {
            const val SELECTED = "selected"
            const val STOP_AFTER = 5_000L
            val DEMO_RETRY = 30.seconds
            val JSON = Json { ignoreUnknownKeys = true }
        }
    }
