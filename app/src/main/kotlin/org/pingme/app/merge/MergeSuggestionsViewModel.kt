// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.merge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pingme.app.inbox.photoFor
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Person
import org.pingme.core.model.PersonId
import org.pingme.core.service.merge.MergeReason
import org.pingme.core.service.merge.MergeRefusedException
import org.pingme.core.service.merge.MergeSuggestion
import org.pingme.core.service.merge.MergeSuggestions
import org.pingme.core.service.merge.Merges
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.ContactRepository
import org.pingme.core.store.SettingsRepository
import javax.inject.Inject

/** One suggestion as the screen shows it, after the user's edits. */
data class SuggestionCard(
    val key: String,
    val reasons: Set<MergeReason>,
    val members: List<PickableChat>,
    /** Chats that could still be added: every other one-to-one chat not in a merged chat. */
    val candidates: List<PickableChat>,
)

data class MergeSuggestionsUiState(
    val loading: Boolean = true,
    val cards: List<SuggestionCard> = emptyList(),
)

/** What the user changed on a suggestion before confirming it. */
private data class Edit(
    val removed: Set<ChatId> = emptySet(),
    val added: List<ChatId> = emptyList(),
)

/** Merge suggestions (UI_DESIGN.md 10.15; owner, Phase 7): PingMe proposes, the user edits and confirms. */
@HiltViewModel
class MergeSuggestionsViewModel
    @Inject
    constructor(
        suggestions: MergeSuggestions,
        chats: ChatRepository,
        accounts: AccountRepository,
        contacts: ContactRepository,
        private val merges: Merges,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val edits = MutableStateFlow<Map<String, Edit>>(emptyMap())
        private val notices = Channel<String>(Channel.BUFFERED)

        /** "Merged" or a refusal, for the snackbar. */
        val messages = notices.receiveAsFlow()

        val state: StateFlow<MergeSuggestionsUiState> =
            combine(suggestions.suggestions, chats.all(), accounts.accounts(), contacts.inChats(), edits) {
                found,
                all,
                accountList,
                people,
                edited,
                ->
                val networkOf = accountList.associate { it.id to it.network }
                val pickable = all.filter { it.kind == ChatKind.DIRECT && it.mergedInto == null && !isParent(it, all) }
                MergeSuggestionsUiState(
                    loading = false,
                    cards =
                        found.map { suggestion ->
                            card(suggestion, edited[suggestion.key] ?: Edit(), pickable, networkOf, people)
                        },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), MergeSuggestionsUiState())

        fun remove(
            key: String,
            chat: ChatId,
        ) = edit(key) { it.copy(removed = it.removed + chat, added = it.added - chat) }

        fun add(
            key: String,
            picked: List<ChatId>,
        ) = edit(key) { it.copy(added = (it.added + picked).distinct(), removed = it.removed - picked.toSet()) }

        fun dismiss(key: String) {
            viewModelScope.launch { settings.dismissMerge(key) }
        }

        fun merge(card: SuggestionCard) {
            viewModelScope.launch {
                try {
                    merges.merge(card.members.map { it.id })
                    settings.dismissMerge(card.key)
                    notices.send(MERGED)
                } catch (e: MergeRefusedException) {
                    notices.send(e.message.orEmpty())
                }
            }
        }

        private fun edit(
            key: String,
            change: (Edit) -> Edit,
        ) = edits.update { it + (key to change(it[key] ?: Edit())) }

        private fun card(
            suggestion: MergeSuggestion,
            edit: Edit,
            pickable: List<Chat>,
            networkOf: Map<org.pingme.core.model.AccountId, NetworkId>,
            people: Map<PersonId, Person>,
        ): SuggestionCard {
            val byId = pickable.associateBy { it.id }
            val members =
                (suggestion.chats.map { it.id } + edit.added)
                    .filter { it !in edit.removed }
                    .distinct()
                    .mapNotNull { byId[it] ?: suggestion.chats.firstOrNull { c -> c.id == it } }
            val inCard = members.map { it.id }.toSet()
            return SuggestionCard(
                key = suggestion.key,
                reasons = suggestion.reasons,
                members = members.map { it.pickable(networkOf, people) },
                candidates = pickable.filter { it.id !in inCard }.map { it.pickable(networkOf, people) },
            )
        }

        private fun Chat.pickable(
            networkOf: Map<org.pingme.core.model.AccountId, NetworkId>,
            people: Map<PersonId, Person>,
        ) = PickableChat(id, nameOverride ?: title, networkOf[accountId] ?: NetworkId.DEMO, photoFor(this, people))

        private fun isParent(
            chat: Chat,
            all: List<Chat>,
        ) = all.any { it.mergedInto == chat.id }

        private companion object {
            const val STOP_AFTER = 5_000L

            /** A marker the screen turns into its own words. */
            const val MERGED = "merged"
        }
    }
