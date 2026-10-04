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
import org.pingme.core.model.AvatarSource
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
    /** The member the merged chat sends from unless another is picked (owner, 2026-10-03). */
    val defaultId: ChatId,
    /** The member whose picture stands for the merged chat; null for the contact's photo. */
    val photoOf: ChatId?,
)

data class MergeSuggestionsUiState(
    val loading: Boolean = true,
    val cards: List<SuggestionCard> = emptyList(),
)

/** What the user changed on a suggestion before confirming it. */
private data class Edit(
    val removed: Set<ChatId> = emptySet(),
    val added: List<ChatId> = emptyList(),
    val defaultId: ChatId? = null,
    /** Set once the user tapped a picture: the member, or null for the contact's photo. */
    val photoPicked: Boolean = false,
    val photoOf: ChatId? = null,
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
        private val contactChats: org.pingme.core.service.merge.ContactChats,
    ) : ViewModel() {
        private val edits = MutableStateFlow<Map<String, Edit>>(emptyMap())
        private val notices = Channel<String>(Channel.BUFFERED)

        /** "Merged" or a refusal, for the snackbar. */
        val messages = notices.receiveAsFlow()

        val state: StateFlow<MergeSuggestionsUiState> =
            combine(suggestions.proposals, chats.all(), accounts.accounts(), contacts.inChats(), edits) {
                proposals,
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
                        proposals.suggestions.map { suggestion ->
                            card(
                                suggestion,
                                edited[suggestion.key] ?: Edit(),
                                pickable,
                                proposals.offers,
                                networkOf,
                                people,
                            )
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

        /** The member the merged chat sends from (owner, 2026-10-03). */
        fun setDefault(
            key: String,
            chat: ChatId,
        ) = edit(key) { it.copy(defaultId = chat) }

        /** The member whose picture stands for the merged chat; null for the contact's photo. */
        fun setPhoto(
            key: String,
            chat: ChatId?,
        ) = edit(key) { it.copy(photoPicked = true, photoOf = chat) }

        fun dismiss(key: String) {
            viewModelScope.launch { settings.dismissMerge(key) }
        }

        fun merge(card: SuggestionCard) {
            viewModelScope.launch {
                try {
                    val sender = card.members.firstOrNull { it.id == card.defaultId }?.accountId
                    val picture =
                        card.members
                            .firstOrNull { it.id == card.photoOf }
                            ?.accountId
                            ?.let { AvatarSource.Network(it) }
                            ?: AvatarSource.Contacts
                    // A contact offered as a text chat becomes a real chat first (owner, 2026-10-04).
                    val ids = contactChats.resolve(card.members.map { it.id })
                    merges.merge(ids, default = sender, avatar = picture)
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

        @Suppress("LongParameterList") // Everything one card is built from.
        private fun card(
            suggestion: MergeSuggestion,
            edit: Edit,
            pickable: List<Chat>,
            offers: org.pingme.core.service.merge.Offers,
            networkOf: Map<org.pingme.core.model.AccountId, NetworkId>,
            people: Map<PersonId, Person>,
        ): SuggestionCard {
            val byId = pickable.associateBy { it.id }
            val offerById = offers.byId
            val shown =
                (suggestion.chats.map { it.id } + suggestion.contacts.map { it.chatId } + edit.added)
                    .filter { it !in edit.removed }
                    .distinct()
                    .mapNotNull { id ->
                        (byId[id] ?: suggestion.chats.firstOrNull { c -> c.id == id })?.pickable(networkOf, people)
                            ?: offerById[id]?.let { PickableChat.ofContact(it, offers.account) }
                    }
            val inCard = shown.map { it.id }.toSet()
            // The contact's photo when any member has one, otherwise the first member's own picture.
            val photoOf =
                if (edit.photoPicked) {
                    edit.photoOf
                } else {
                    shown.firstOrNull { it.hasContactPhoto }?.let { null } ?: shown.firstOrNull { it.photo != null }?.id
                }
            return SuggestionCard(
                key = suggestion.key,
                reasons = suggestion.reasons,
                members = shown,
                candidates =
                    pickable.filter { it.id !in inCard }.map { it.pickable(networkOf, people) } +
                        offers.list.filter { it.chatId !in inCard }.map { PickableChat.ofContact(it, offers.account) },
                defaultId = edit.defaultId?.takeIf { it in inCard } ?: shown.first().id,
                photoOf = photoOf?.takeIf { it in inCard },
            )
        }

        private fun Chat.pickable(
            networkOf: Map<org.pingme.core.model.AccountId, NetworkId>,
            people: Map<PersonId, Person>,
        ) = PickableChat.of(this, networkOf[accountId] ?: NetworkId.DEMO, people)

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
