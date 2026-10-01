// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pingme.app.appearance.AppearanceRepository
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.AppSettings
import org.pingme.core.model.Chat
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.SettingsRepository
import org.pingme.core.ui.theme.Appearance
import javax.inject.Inject

/** Everything the Settings screens show (BUILD_PLAN.md P2.6). */
data class SettingsState(
    val app: AppSettings = AppSettings(),
    val accounts: List<Account> = emptyList(),
    val quickReactions: List<String> = emptyList(),
    val doubleTap: String = "",
    val recentEmoji: List<String> = emptyList(),
    val appearance: Appearance = Appearance(),
    val showGeneral: Boolean = true,
    /** Chats the user has obscured, listed under Privacy (UI_DESIGN.md 10.10). */
    val obscured: List<Chat> = emptyList(),
)

/** What the Settings pages can change; the view model does it. */
interface SettingsActions {
    fun update(change: (AppSettings) -> AppSettings)

    fun updateAppearance(change: (Appearance) -> Appearance)

    fun setQuickReactions(set: List<String>)

    fun setDoubleTap(emoji: String)

    fun setShowGeneral(show: Boolean)

    /** Renames an account, recolours its badge, or changes its notifications or inbox visibility. */
    fun updateAccount(
        id: AccountId,
        change: (Account) -> Account,
    )

    fun unobscure(chat: Chat)
}

/** Settings: accounts, privacy, reactions, motion, and the rest (UI_DESIGN.md 4, 5.4, 6, 10). */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val accounts: AccountRepository,
        private val chats: ChatRepository,
        private val appearance: AppearanceRepository,
    ) : ViewModel(),
        SettingsActions {
        val state: StateFlow<SettingsState> =
            combine(
                settings.app,
                accounts.accounts(),
                combine(settings.quickReactions, settings.doubleTapReaction, settings.recentEmoji, ::Triple),
                combine(appearance.appearance, settings.instagramShowGeneral, ::Pair),
                chats.all(),
            ) { app, all, (quick, double, recent), (look, general), every ->
                SettingsState(app, all, quick, double, recent, look, general, every.filter { it.isObscured })
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), SettingsState())

        override fun update(change: (AppSettings) -> AppSettings) = launch { settings.updateApp(change) }

        override fun updateAppearance(change: (Appearance) -> Appearance) = launch { appearance.update(change) }

        override fun setQuickReactions(set: List<String>) = launch { settings.setQuickReactions(set) }

        override fun setDoubleTap(emoji: String) = launch { settings.setDoubleTapReaction(emoji) }

        override fun setShowGeneral(show: Boolean) = launch { settings.setInstagramShowGeneral(show) }

        override fun updateAccount(
            id: AccountId,
            change: (Account) -> Account,
        ) = launch { accounts.get(id)?.let { accounts.upsert(change(it)) } }

        override fun unobscure(chat: Chat) = launch { chats.update(chat.id) { it.copy(isObscured = false) } }

        private fun launch(block: suspend () -> Unit) {
            viewModelScope.launch { block() }
        }

        private companion object {
            const val STOP_AFTER = 5_000L
        }
    }
