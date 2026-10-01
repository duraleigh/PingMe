// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.preview

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.pingme.app.inbox.InboxBarItem
import org.pingme.app.settings.AccountList
import org.pingme.app.settings.AccountPage
import org.pingme.app.settings.BackupActions
import org.pingme.app.settings.BackupPage
import org.pingme.app.settings.BackupStatus
import org.pingme.app.settings.MotionPage
import org.pingme.app.settings.NotificationsPage
import org.pingme.app.settings.PrivacyPage
import org.pingme.app.settings.ReactionsPage
import org.pingme.app.settings.SettingsActions
import org.pingme.app.settings.SettingsHome
import org.pingme.app.settings.SettingsNavigation
import org.pingme.app.settings.SettingsState
import org.pingme.app.settings.SpaceActions
import org.pingme.app.settings.SpacesPage
import org.pingme.app.settings.StoragePage
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.AppSettings
import org.pingme.core.model.Chat
import org.pingme.core.model.KeywordRule
import org.pingme.core.model.NotificationProfile
import org.pingme.core.model.Space
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode

// Previews of every Settings page (BUILD_PLAN.md P2.8).

/** Settings actions that do nothing, for previews. */
private object NoActions : SettingsActions, SpaceActions, BackupActions {
    override fun update(change: (AppSettings) -> AppSettings) = Unit

    override fun updateAppearance(change: (Appearance) -> Appearance) = Unit

    override fun setQuickReactions(set: List<String>) = Unit

    override fun setDoubleTap(emoji: String) = Unit

    override fun setShowGeneral(show: Boolean) = Unit

    override fun updateAccount(
        id: AccountId,
        change: (Account) -> Account,
    ) = Unit

    override fun unobscure(chat: Chat) = Unit

    override fun saveKeyword(
        rule: KeywordRule,
        sound: NotificationProfile,
    ) = Unit

    override fun deleteKeyword(rule: KeywordRule) = Unit

    override fun saveSpace(space: Space) = Unit

    override fun deleteSpace(space: Space) = Unit

    override fun setBar(items: List<InboxBarItem>) = Unit

    override fun exportTo(uri: Uri) = Unit

    override fun restoreFrom(uri: Uri) = Unit
}

private val state =
    SettingsState(
        accounts = listOf(PreviewData.account),
        quickReactions = listOf("❤️", "😂", "👍", "😮", "😢", "🙏"),
        doubleTap = "❤️",
        chats = listOf(PreviewData.chat),
    )

@Composable
private fun Page(content: @Composable () -> Unit) =
    PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
        Surface { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
    }

@Preview(name = "Settings", widthDp = 411, heightDp = 891)
@Composable
internal fun SettingsHomePreview() = Page { SettingsHome(SettingsNavigation({}, {}, {}, {}, {}, { _, _ -> })) }

@Preview(name = "Settings: accounts", widthDp = 411, heightDp = 891)
@Composable
internal fun AccountsPreview() =
    Page {
        AccountList(state, listOf(PreviewData.account.network), {}, {})
        AccountPage(state, PreviewData.account.id, NoActions, {})
    }

@Preview(name = "Settings: notifications and privacy", widthDp = 411, heightDp = 1600)
@Composable
internal fun NotificationsPrivacyPreview() =
    Page {
        NotificationsPage(state, NoActions)
        PrivacyPage(state, NoActions)
    }

@Preview(name = "Settings: reactions and motion", widthDp = 411, heightDp = 1200)
@Composable
internal fun ReactionsMotionPreview() =
    Page {
        ReactionsPage(state, NoActions)
        MotionPage(state, NoActions)
    }

@Preview(name = "Settings: storage, spaces, backup", widthDp = 411, heightDp = 1600)
@Composable
internal fun StorageSpacesBackupPreview() =
    Page {
        StoragePage(state, NoActions)
        SpacesPage(state, NoActions)
        BackupPage(BackupStatus.SAVED, NoActions, {})
    }
