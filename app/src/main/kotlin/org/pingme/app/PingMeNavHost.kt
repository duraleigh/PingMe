// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.pingme.app.appearance.AppearanceRoute
import org.pingme.app.chat.ChatRoute
import org.pingme.app.chat.NoChatPicked
import org.pingme.app.details.ChatDetailsRoute
import org.pingme.app.details.DetailsNavigation
import org.pingme.app.inbox.ChatListRoute
import org.pingme.app.inbox.InboxNavigation
import org.pingme.app.inbox.InboxRoute
import org.pingme.app.inbox.MenuActions
import org.pingme.app.inbox.NewChatRoute
import org.pingme.app.inbox.SearchRoute
import org.pingme.app.inbox.route
import org.pingme.app.login.LoginRoute
import org.pingme.app.settings.SettingsNavigation
import org.pingme.app.settings.SettingsPage
import org.pingme.app.settings.SettingsRoute
import org.pingme.app.setup.SetupNavigation
import org.pingme.app.setup.SetupRoute
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.store.ChatRepository
import javax.inject.Inject

/** The inbox, and on wide screens the open chat beside it. */
@Serializable
object Home

@Serializable
object AppearanceStudio

@Serializable
object Search

/** Opens a chat from anywhere: the inbox's detail pane shows it (a full screen on phones). */
@Serializable
data class OpenChat(
    val chatId: String,
)

/** A Settings page (BUILD_PLAN.md P2.6); [account] picks one account's page. */
@Serializable
data class SettingsDest(
    val page: String = SettingsPage.HOME.name,
    val account: String? = null,
)

/** First-run setup (BUILD_PLAN.md P2.7). */
@Serializable
object Setup

/** A network's login; [account] logs that account in again, [fromSetup] ends setup when it finishes. */
@Serializable
data class LoginDest(
    val network: String,
    val account: String? = null,
    val fromSetup: Boolean = false,
)

/** Chat details for one chat (UI_DESIGN.md 3.4), over the chat it came from. */
@Serializable
data class ChatDetails(
    val chatId: String,
)

// First-run setup and every network login (BUILD_PLAN.md P2.7).
private fun NavGraphBuilder.setupAndLogin(nav: NavController) {
    composable<Setup> {
        val activity = LocalActivity.current
        SetupRoute(
            SetupNavigation(
                onLogin = { nav.navigate(LoginDest(it.name, fromSetup = true)) },
                onDone = { nav.navigate(Home) { popUpTo<Setup> { inclusive = true } } },
                onLeave = { activity?.finish() },
            ),
        )
    }
    composable<LoginDest> { entry ->
        val again = entry.arguments?.getString("account") != null
        val fromSetup = entry.arguments?.getBoolean("fromSetup") == true
        LoginRoute(
            again,
            onFinish = {
                if (fromSetup) nav.navigate(Home) { popUpTo<Setup> { inclusive = true } } else nav.popBackStack()
            },
            onBack = { nav.popBackStack() },
        )
    }
}

@Composable
fun PingMeNavHost(
    startAtSetup: Boolean,
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = if (startAtSetup) Setup else Home, modifier = modifier) {
        composable<Home> { InboxHome(nav) }
        setupAndLogin(nav)
        composable<OpenChat> { entry ->
            val id = ChatId(entry.arguments?.getString("chatId").orEmpty())
            ChatRoute(id, onBack = { nav.popBackStack() }, onDetails = { nav.navigate(ChatDetails(id.value)) })
        }
        composable<ChatDetails> { entry ->
            val id = ChatId(entry.arguments?.getString("chatId").orEmpty())
            ChatDetailsRoute(
                id,
                DetailsNavigation(
                    onBack = { nav.popBackStack() },
                    onBackToChat = { nav.popBackStack() },
                    // Blocked or deleted: the chat behind is gone too.
                    onLeft = { nav.popBackStack(Home, inclusive = false) },
                ),
            )
        }
        composable<AppearanceStudio> { AppearanceRoute(onBack = { nav.popBackStack() }) }
        composable<SettingsDest> { entry ->
            val context = LocalContext.current
            val page =
                runCatching {
                    SettingsPage.valueOf(entry.arguments?.getString("page").orEmpty())
                }.getOrDefault(SettingsPage.HOME)
            SettingsRoute(
                page,
                SettingsNavigation(
                    onBack = { nav.popBackStack() },
                    onPage = { nav.navigate(SettingsDest(it.name)) },
                    onAccount = { nav.navigate(SettingsDest(SettingsPage.ACCOUNT.name, it.value)) },
                    onAppearance = { nav.navigate(AppearanceStudio) },
                    onRestart = {
                        org.pingme.app.settings
                            .restartApp(context)
                    },
                    onLogin = { network, account -> nav.navigate(LoginDest(network.name, account?.value)) },
                ),
                account = entry.arguments?.getString("account")?.let(::AccountId),
            )
        }
        composable<ChatListRoute> {
            ChatListRoute(onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(OpenChat(it.value)) })
        }
        composable<Search> {
            SearchRoute(
                onBack = { nav.popBackStack() },
                onOpenChat = { nav.navigate(OpenChat(it.value)) },
            )
        }
        composable<NewChatRoute> {
            NewChatRoute(onBack = { nav.popBackStack() }, onOpenChat = {
                nav.navigate(OpenChat(it.value)) { popUpTo(Home) }
            })
        }
    }
}

/**
 * The inbox as a list-detail layout (BUILD_PLAN.md P2.3, UI_DESIGN.md 3.7): on phones the
 * chat replaces the list, on tablets and unfolded foldables it opens beside it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun InboxHome(nav: NavController) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                InboxRoute(
                    InboxNavigation(
                        onOpenChat = { id ->
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id.value) }
                        },
                        onSearch = { nav.navigate(Search) },
                        onNewChat = { nav.navigate(NewChatRoute(group = false)) },
                        onNewGroup = { nav.navigate(NewChatRoute(group = true)) },
                        onFix = { account -> fix(context, account) },
                        menu =
                            MenuActions(
                                onAppearance = { nav.navigate(AppearanceStudio) },
                                onList = { nav.navigate(it.route()) },
                                onEditBar = {},
                                onSettings = { nav.navigate(SettingsDest()) },
                                onAccounts = { nav.navigate(SettingsDest(SettingsPage.ACCOUNTS.name)) },
                            ),
                    ),
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val id = navigator.currentDestination?.contentKey
                if (id == null) {
                    NoChatPicked()
                } else {
                    ChatRoute(
                        ChatId(id),
                        onBack =
                            if (navigator.canNavigateBack()) {
                                (
                                    {
                                        scope.launch { navigator.navigateBack() }
                                    }
                                )
                            } else {
                                null
                            },
                        onDetails = { nav.navigate(ChatDetails(id)) },
                    )
                }
            }
        },
    )
}

/** "Tap to fix": opens the app or page the account's problem points at, when there is one. */
private fun fix(
    context: Context,
    account: Account,
) {
    val link = (account.state as? ConnectionState.ActionNeeded)?.deepLink ?: return
    val intent =
        context.packageManager.getLaunchIntentForPackage(link)
            ?: Intent(Intent.ACTION_VIEW, link.toUri())
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on this phone opens it; Settings > Accounts (P2.6) is the other way to fix it.
    }
}
