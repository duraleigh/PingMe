// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pingme.app.appearance.AppearanceRepository
import org.pingme.app.inbox.InboxBarConfig
import org.pingme.app.inbox.InboxBarItem
import org.pingme.app.inbox.InboxBarRepository
import org.pingme.core.connector.ConnectorRegistry
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.AppSettings
import org.pingme.core.model.Chat
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.NetworkId
import org.pingme.core.model.NotificationProfile
import org.pingme.core.model.Space
import org.pingme.core.store.AccountRepository
import org.pingme.core.store.BackupStore
import org.pingme.core.store.ChatRepository
import org.pingme.core.store.RestoreProblem
import org.pingme.core.store.SettingsRepository
import org.pingme.core.ui.theme.Appearance
import java.io.IOException
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
    /** Every chat, for keywords that apply to some chats only (UI_DESIGN.md 10.9). */
    val chats: List<Chat> = emptyList(),
    val keywords: List<KeywordRule> = emptyList(),
    val spaces: List<Space> = emptyList(),
    /** The bottom bar's buttons, null standing for All (UI_DESIGN.md 10.4). */
    val bar: List<InboxBarItem?> = listOf(null),
    val backup: BackupStatus = BackupStatus.IDLE,
)

/** How the last backup or restore went. */
enum class BackupStatus { IDLE, WORKING, SAVED, SAVE_FAILED, RESTORED, NOT_A_BACKUP, NEWER_VERSION }

/** Saving the chats to a file and bringing them back (BUILD_PLAN.md P2.6). */
interface BackupActions {
    fun exportTo(uri: Uri)

    fun restoreFrom(uri: Uri)
}

/** Spaces and the bottom bar (UI_DESIGN.md 10.4). */
interface SpaceActions {
    /** Adds or changes a space the user made from any chats. */
    fun saveSpace(space: Space)

    fun deleteSpace(space: Space)

    fun setBar(buttons: List<InboxBarItem?>)
}

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

    /** Adds or changes a keyword rule, with its sound and vibration. */
    fun saveKeyword(
        rule: KeywordRule,
        sound: NotificationProfile,
    )

    fun deleteKeyword(rule: KeywordRule)
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
        private val bar: InboxBarRepository,
        private val backups: BackupStore,
        @param:ApplicationContext private val context: Context,
        registry: ConnectorRegistry,
    ) : ViewModel(),
        SettingsActions,
        SpaceActions,
        BackupActions {
        private val backupStatus = MutableStateFlow(BackupStatus.IDLE)

        /** The networks this build can connect, for Add account. */
        val networks: List<NetworkId> = NetworkId.entries.filter { it in registry.networks }

        val state: StateFlow<SettingsState> =
            combine(
                settings.app,
                accounts.accounts(),
                combine(settings.quickReactions, settings.doubleTapReaction, settings.recentEmoji, ::Triple),
                combine(appearance.appearance, settings.instagramShowGeneral, ::Pair),
                combine(chats.all(), settings.keywordRules(), chats.spaces(), bar.config, backupStatus, ::Lists),
            ) { app, all, (quick, double, recent), (look, general), lists ->
                SettingsState(
                    app,
                    all,
                    quick,
                    double,
                    recent,
                    look,
                    general,
                    lists.chats.filter { it.isObscured },
                    lists.chats,
                    lists.keywords,
                    lists.spaces,
                    (lists.bar ?: defaultBar(all)).buttons,
                    lists.backup,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), SettingsState())

        override fun update(change: (AppSettings) -> AppSettings) = launch { settings.updateApp(change) }

        override fun saveSpace(space: Space) = launch { chats.upsertSpace(space) }

        override fun exportTo(uri: Uri) =
            launch {
                backupStatus.value = BackupStatus.WORKING
                val saved =
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { backups.export(it) } != null
                        }
                    } catch (_: IOException) {
                        false
                    }
                backupStatus.value = if (saved) BackupStatus.SAVED else BackupStatus.SAVE_FAILED
            }

        override fun restoreFrom(uri: Uri) =
            launch {
                backupStatus.value = BackupStatus.WORKING
                val problem =
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { backups.restore(it) }
                                ?: RestoreProblem.NOT_A_BACKUP
                        }
                    } catch (_: IOException) {
                        RestoreProblem.NOT_A_BACKUP
                    }
                backupStatus.value =
                    when (problem) {
                        null -> BackupStatus.RESTORED
                        RestoreProblem.NOT_A_BACKUP -> BackupStatus.NOT_A_BACKUP
                        RestoreProblem.NEWER_VERSION -> BackupStatus.NEWER_VERSION
                    }
            }

        override fun deleteSpace(space: Space) =
            launch {
                chats.deleteSpace(space.id)
                // A deleted space leaves the bar too.
                bar.update(defaultBar(accounts.getAll())) { config ->
                    config.copy(items = config.items - InboxBarItem.Space(space.id))
                }
            }

        override fun setBar(buttons: List<InboxBarItem?>) =
            launch { bar.update(defaultBar(accounts.getAll())) { it.withButtons(buttons) } }

        private fun defaultBar(all: List<Account>) = InboxBarConfig.default(all.map { it.network })

        private data class Lists(
            val chats: List<Chat>,
            val keywords: List<KeywordRule>,
            val spaces: List<Space>,
            val bar: InboxBarConfig?,
            val backup: BackupStatus,
        )

        override fun updateAppearance(change: (Appearance) -> Appearance) = launch { appearance.update(change) }

        override fun setQuickReactions(set: List<String>) = launch { settings.setQuickReactions(set) }

        override fun setDoubleTap(emoji: String) = launch { settings.setDoubleTapReaction(emoji) }

        override fun setShowGeneral(show: Boolean) = launch { settings.setInstagramShowGeneral(show) }

        override fun updateAccount(
            id: AccountId,
            change: (Account) -> Account,
        ) = launch { accounts.get(id)?.let { accounts.upsert(change(it)) } }

        override fun unobscure(chat: Chat) = launch { chats.update(chat.id) { it.copy(isObscured = false) } }

        override fun saveKeyword(
            rule: KeywordRule,
            sound: NotificationProfile,
        ) = launch {
            settings.upsertKeywordRule(rule)
            settings.updateApp { app ->
                val notify = app.notifications
                app.copy(notifications = notify.copy(keywords = notify.keywords + (rule.id.value to sound)))
            }
        }

        override fun deleteKeyword(rule: KeywordRule) =
            launch {
                settings.deleteKeywordRule(rule.id)
                settings.updateApp { app ->
                    val notify = app.notifications
                    app.copy(notifications = notify.copy(keywords = notify.keywords - rule.id.value))
                }
            }

        private fun launch(block: suspend () -> Unit) {
            viewModelScope.launch { block() }
        }

        private companion object {
            const val STOP_AFTER = 5_000L
        }
    }
