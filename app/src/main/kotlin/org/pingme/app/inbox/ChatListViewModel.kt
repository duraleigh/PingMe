// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.annotation.Keep
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.pingme.app.R
import org.pingme.core.connector.UnsupportedCapabilityException
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatFolder
import org.pingme.core.model.SpaceId
import org.pingme.core.service.ChatActions
import org.pingme.core.service.TypingTracker
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.MessageRepository
import org.pingme.core.ui.theme.SwipeAction
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Instant

/** Which of the avatar menu's lists to show. Kept, as a navigation argument, so release builds can still read it. */
@Keep
enum class ListKind { ARCHIVED, LOW_PRIORITY, REQUESTS, GENERAL, SPACE }

/** Navigation route for one of the avatar menu's lists. */
@Serializable
data class ChatListRoute(
    val kind: ListKind,
    val spaceId: String? = null,
    val title: String? = null,
)

fun ChatList.route() =
    when (this) {
        ChatList.Archived -> ChatListRoute(ListKind.ARCHIVED)
        ChatList.LowPriority -> ChatListRoute(ListKind.LOW_PRIORITY)
        ChatList.Requests -> ChatListRoute(ListKind.REQUESTS)
        ChatList.General -> ChatListRoute(ListKind.GENERAL)
        is ChatList.InSpace -> ChatListRoute(ListKind.SPACE, space.id.value, space.title)
    }

data class ChatListUiState(
    val rows: List<ChatRow> = emptyList(),
    val now: Instant = Instant.DISTANT_PAST,
    val loading: Boolean = true,
)

/** Archived, Low priority, Requests, General, or one space (UI_DESIGN.md 3.1, 6.4, 10.4, 10.7). */
@HiltViewModel
class ChatListViewModel
    @Inject
    constructor(
        chats: ChatRepository,
        messages: MessageRepository,
        accounts: AccountRepository,
        typing: TypingTracker,
        private val actions: ChatActions,
        clock: Clock,
        saved: SavedStateHandle,
    ) : ViewModel() {
        val route: ChatListRoute = saved.toRoute()
        private val rowActions = RowActions(viewModelScope, actions)
        val messages = rowActions.messages

        private val source: Flow<List<Chat>> =
            when (route.kind) {
                ListKind.ARCHIVED -> {
                    chats.archived()
                }

                ListKind.LOW_PRIORITY -> {
                    chats.lowPriority()
                }

                ListKind.REQUESTS -> {
                    chats.folder(ChatFolder.REQUESTS)
                }

                ListKind.GENERAL -> {
                    chats.folder(ChatFolder.GENERAL)
                }

                ListKind.SPACE -> {
                    val id = SpaceId(requireNotNull(route.spaceId))
                    combine(chats.inbox(), chats.spaces()) { inbox, spaces ->
                        val members =
                            spaces
                                .find { it.id == id }
                                ?.chatIds
                                .orEmpty()
                                .toSet()
                        inbox.filter { it.spaceId == id || it.id in members }
                    }
                }
            }

        val state: StateFlow<ChatListUiState> =
            combine(source, messages.lastMessages(), accounts.accounts(), typing.typing, rowActions.pendingDeletes) {
                list,
                last,
                accountList,
                typingNow,
                hidden,
                ->
                val networkOf = accountList.associate { it.id to it.network }
                ChatListUiState(
                    rows =
                        list
                            .filter { it.id !in hidden }
                            .map { chat ->
                                ChatRow(
                                    chat,
                                    networkOf[chat.accountId] ?: org.pingme.core.model.NetworkId.DEMO,
                                    last[chat.id],
                                    !typingNow[chat.id].isNullOrEmpty(),
                                )
                            },
                    now = clock.now(),
                    loading = false,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), ChatListUiState())

        fun swipe(
            row: ChatRow,
            action: SwipeAction,
        ) = rowActions.swipe(row, action)

        fun perform(
            row: ChatRow,
            action: ChatAction,
        ) = rowActions.perform(row, action)

        /** Accept, Decline, or Block a message request (UI_DESIGN.md 6.4). */
        fun respond(
            row: ChatRow,
            response: RequestResponse,
        ) {
            viewModelScope.launch {
                try {
                    when (response) {
                        RequestResponse.ACCEPT -> actions.respondToRequest(row.id, accept = true)
                        RequestResponse.DECLINE -> actions.respondToRequest(row.id, accept = false)
                        RequestResponse.BLOCK -> actions.block(row.id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: UnsupportedCapabilityException) {
                    rowActions.tell(R.string.request_not_possible, row.title)
                }
            }
        }

        private companion object {
            const val STOP_AFTER = 5_000L
        }
    }

enum class RequestResponse { ACCEPT, DECLINE, BLOCK }
