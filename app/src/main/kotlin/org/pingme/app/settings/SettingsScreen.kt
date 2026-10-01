// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.core.model.AccountId
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.R as UiR

/** One page of Settings. [ACCOUNT] is one account's page. */
enum class SettingsPage(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int = 0,
    @param:DrawableRes val icon: Int = 0,
) {
    HOME(R.string.settings_title),
    ACCOUNTS(R.string.settings_accounts, R.string.settings_accounts_summary, UiR.drawable.ic_account_circle),
    APPEARANCE(R.string.settings_appearance, R.string.settings_appearance_summary, UiR.drawable.ic_palette),
    NOTIFICATIONS(
        R.string.settings_notifications,
        R.string.settings_notifications_summary,
        UiR.drawable.ic_notifications,
    ),
    PRIVACY(R.string.settings_privacy, R.string.settings_privacy_summary, UiR.drawable.ic_lock),
    REACTIONS(R.string.settings_reactions, R.string.settings_reactions_summary, UiR.drawable.ic_add_reaction),
    MOTION(R.string.settings_motion, R.string.settings_motion_summary, UiR.drawable.ic_animation),
    STORAGE(R.string.settings_storage, R.string.settings_storage_summary, UiR.drawable.ic_download),
    SPACES(R.string.settings_spaces, R.string.settings_spaces_summary, UiR.drawable.ic_apps),
    BACKUP(R.string.settings_backup, R.string.settings_backup_summary, UiR.drawable.ic_upload),
    ACCOUNT(R.string.settings_accounts),
}

/** Where Settings goes: another page, an account's page, the Appearance studio, or back. */
class SettingsNavigation(
    val onBack: () -> Unit,
    val onPage: (SettingsPage) -> Unit,
    val onAccount: (AccountId) -> Unit,
    val onAppearance: () -> Unit,
    /** After a restore the app starts again on the restored database. */
    val onRestart: () -> Unit,
    /** A network's login: a new account, or [AccountId] logging in again. */
    val onLogin: (NetworkId, AccountId?) -> Unit,
)

/** A Settings page with the shared view model (BUILD_PLAN.md P2.6). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsRoute(
    page: SettingsPage,
    navigation: SettingsNavigation,
    modifier: Modifier = Modifier,
    account: AccountId? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val named =
        state.accounts
            .firstOrNull { it.id == account }
            ?.displayName
            .orEmpty()
    val title = if (page == SettingsPage.ACCOUNT) named else stringResource(page.title)
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        navigation.onBack,
                    ) { Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            pageItems(page, state, account, navigation, viewModel)
        }
    }
}

// One page's content; each page gets only the actions it needs.
private fun LazyListScope.pageItems(
    page: SettingsPage,
    state: SettingsState,
    account: AccountId?,
    navigation: SettingsNavigation,
    viewModel: SettingsViewModel,
) {
    val actions: SettingsActions = viewModel
    val spaces: SpaceActions = viewModel
    val backup: BackupActions = viewModel
    when (page) {
        SettingsPage.HOME -> {
            home(navigation)
        }

        SettingsPage.ACCOUNTS -> {
            item {
                AccountList(state, viewModel.networks, navigation.onAccount, { navigation.onLogin(it, null) })
            }
        }

        SettingsPage.ACCOUNT -> {
            item {
                account?.let {
                    AccountPage(
                        state,
                        it,
                        actions,
                        { a -> navigation.onLogin(a.network, a.id) },
                    )
                }
            }
        }

        SettingsPage.PRIVACY -> {
            item { PrivacyPage(state, actions) }
        }

        SettingsPage.REACTIONS -> {
            item { ReactionsPage(state, actions) }
        }

        SettingsPage.MOTION -> {
            item { MotionPage(state, actions) }
        }

        SettingsPage.NOTIFICATIONS -> {
            item { NotificationsPage(state, actions) }
        }

        SettingsPage.STORAGE -> {
            item { StoragePage(state, actions) }
        }

        SettingsPage.SPACES -> {
            item { SpacesPage(state, spaces) }
        }

        SettingsPage.BACKUP -> {
            item { BackupPage(state.backup, backup, navigation.onRestart) }
        }

        // Appearance is its own studio screen, opened from the list.
        SettingsPage.APPEARANCE -> {
            Unit
        }
    }
}

// The list of pages; Appearance opens the studio it already has.
private fun LazyListScope.home(navigation: SettingsNavigation) {
    items(SettingsPage.entries.filter { it.summary != 0 }) { page ->
        ListItem(
            headlineContent = { Text(stringResource(page.title)) },
            supportingContent = { Text(stringResource(page.summary)) },
            leadingContent = { Icon(painterResource(page.icon), null) },
            modifier =
                Modifier.clickable {
                    if (page == SettingsPage.APPEARANCE) navigation.onAppearance() else navigation.onPage(page)
                },
        )
    }
}
