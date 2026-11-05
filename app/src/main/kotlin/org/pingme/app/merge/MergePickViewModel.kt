// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.NetworkId
import org.pingme.core.service.merge.MergeRefusedException
import org.pingme.core.service.merge.MergeSuggestions
import org.pingme.core.service.merge.Merges
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import javax.inject.Inject

data class MergePickUiState(
    /** Every one-to-one chat not yet in a merged chat, from every network. */
    val candidates: List<PickableChat> = emptyList(),
    val suggestions: Int = 0,
)

/** Settings > Merge chats: the chats on offer, how many suggestions wait, and the merge itself. */
@HiltViewModel
class MergePickViewModel
    @Inject
    constructor(
        chats: ChatRepository,
        accounts: AccountRepository,
        contacts: ContactRepository,
        suggestions: MergeSuggestions,
        private val merges: Merges,
        private val contactChats: org.pingme.core.service.merge.ContactChats,
    ) : ViewModel() {
        private val notices = Channel<String>(Channel.BUFFERED)
        val messages = notices.receiveAsFlow()

        val state: StateFlow<MergePickUiState> =
            combine(chats.all(), accounts.accounts(), contacts.inChats(), suggestions.proposals) {
                all,
                accountList,
                people,
                proposals,
                ->
                val networkOf = accountList.associate { it.id to it.network }
                val parents = all.mapNotNull { it.mergedInto }.toSet()
                val offers = proposals.offers
                MergePickUiState(
                    candidates =
                        all
                            .filter { it.kind == ChatKind.DIRECT && it.mergedInto == null && it.id !in parents }
                            .map { PickableChat.of(it, networkOf[it.accountId] ?: NetworkId.DEMO, people) } +
                            // Phone contacts with a number, as the text chats they would become (owner, 2026-10-04).
                            offers.list.map { PickableChat.ofContact(it, offers.account) },
                    suggestions = proposals.suggestions.size,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), MergePickUiState())

        fun merge(picked: List<ChatId>) {
            viewModelScope.launch {
                try {
                    merges.merge(contactChats.resolve(picked))
                    notices.send(MERGED)
                } catch (e: MergeRefusedException) {
                    notices.send(e.message.orEmpty())
                }
            }
        }

        companion object {
            const val STOP_AFTER = 5_000L
            const val MERGED = "merged"
        }
    }
