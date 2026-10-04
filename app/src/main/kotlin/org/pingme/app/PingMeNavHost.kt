// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.AnimatedPaneScope
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldPaneScope
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
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
import org.pingme.app.web.WebPageDest
import org.pingme.app.web.WebPageRoute
import org.pingme.core.model.Account
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.store.ChatRepository
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ScreenMotion
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

/** The share picker, for text or files another app handed PingMe (owner, Gate G3). */
@Serializable
object ShareDest

/** Merge suggestions (UI_DESIGN.md 10.15; owner, Phase 7). */
@Serializable
object MergeSuggestionsDest

// One chat, and a network's own page opened inside PingMe from a link card (owner, Gate G7).
private fun NavGraphBuilder.chatAndPages(nav: NavController) {
    composable<OpenChat> { entry ->
        val id = ChatId(entry.arguments?.getString("chatId").orEmpty())
        ChatRoute(
            id,
            onBack = { nav.popBackStack() },
            onDetails = { nav.navigate(ChatDetails(id.value)) },
            onOpenPage = { url, account -> nav.navigate(WebPageDest(url, account.value)) },
        )
    }
    composable<MergeSuggestionsDest> {
        org.pingme.app.merge
            .MergeSuggestionsRoute(onBack = { nav.popBackStack() })
    }
    composable<WebPageDest> { entry ->
        WebPageRoute(
            entry.arguments?.getString("url").orEmpty(),
            entry.arguments?.getString("accountId").orEmpty(),
            onBack = { nav.popBackStack() },
        )
    }
}

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
    OpenTappedChat(nav)
    OpenShare(nav)
    MotionNavHost(nav, startDestination = if (startAtSetup) Setup else Home, modifier = modifier) {
        composable<Home> { InboxHome(nav) }
        setupAndLogin(nav)
        chatAndPages(nav)
        composable<ChatDetails> { entry ->
            val id = ChatId(entry.arguments?.getString("chatId").orEmpty())
            ChatDetailsRoute(
                id,
                DetailsNavigation(
                    onBack = { nav.popBackStack() },
                    onBackToChat = { nav.popBackStack() },
                    // Blocked or deleted: the chat behind is gone too.
                    onLeft = { nav.popBackStack(Home, inclusive = false) },
                    onMerged = { merged ->
                        nav.popBackStack(Home, inclusive = false)
                        nav.navigate(OpenChat(merged.value))
                    },
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
        pickers(nav)
    }
}

// New chat, new group, and the share picker: screens that end by opening a chat.
private fun NavGraphBuilder.pickers(nav: NavController) {
    composable<NewChatRoute> {
        NewChatRoute(onBack = { nav.popBackStack() }, onOpenChat = {
            nav.navigate(OpenChat(it.value)) { popUpTo(Home) }
        })
    }
    composable<ShareDest> {
        org.pingme.app.share.ShareRoute(
            onBack = { nav.popBackStack() },
            onOpenChat = { nav.navigate(OpenChat(it.value)) { popUpTo(Home) } },
            onDone = { nav.popBackStack(Home, inclusive = false) },
        )
    }
}

/** Something shared from another app opens the picker, over whatever was showing (owner, Gate G3). */
@Composable
private fun OpenShare(nav: NavController) {
    val shares = hiltViewModel<org.pingme.app.share.ShareRequestViewModel>().shares
    val requested by shares.requested.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(requested) {
        if (requested != null) nav.navigate(ShareDest) { launchSingleTop = true }
    }
}

/** A tapped notification opens its chat, over whatever was showing (owner, Gate G2). */
@Composable
private fun OpenTappedChat(nav: NavController) {
    val taps = hiltViewModel<org.pingme.app.notify.NotificationTapViewModel>().taps
    val requested by taps.requested.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(requested) {
        val chat = requested ?: return@LaunchedEffect
        nav.navigate(OpenChat(chat.value)) { launchSingleTop = true }
        taps.consume()
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
            MotionPane(atEnd = false) {
                InboxRoute(
                    InboxNavigation(
                        onOpenChat = { id ->
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id.value) }
                        },
                        onSearch = { nav.navigate(Search) },
                        onNewChat = { nav.navigate(NewChatRoute(group = false)) },
                        onNewGroup = { nav.navigate(NewChatRoute(group = true)) },
                        onFix = { account -> fix(context, account) },
                        onMergeSuggestions = { nav.navigate(MergeSuggestionsDest) },
                        menu =
                            MenuActions(
                                onAppearance = { nav.navigate(AppearanceStudio) },
                                onList = { nav.navigate(it.route()) },
                                onEditBar = {},
                                onSettings = { nav.navigate(SettingsDest()) },
                                onAccounts = { nav.navigate(SettingsDest(SettingsPage.ACCOUNTS.name)) },
                                onMergeSuggestions = { nav.navigate(MergeSuggestionsDest) },
                            ),
                    ),
                )
            }
        },
        detailPane = {
            MotionPane(atEnd = true) {
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
                        onOpenPage = { url, account -> nav.navigate(WebPageDest(url, account.value)) },
                    )
                }
            }
        },
    )
}

/** A NavHost whose screens come and go as the motion level says (UI_DESIGN.md 4.5). */
@Composable
private fun MotionNavHost(
    nav: NavHostController,
    startDestination: Any,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    val level = PingMeTheme.motion
    val scheme = MaterialTheme.motionScheme
    NavHost(
        nav,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { ScreenMotion.enter(level, scheme, fromEnd = true) },
        exitTransition = { ScreenMotion.exit(level, scheme, toEnd = false) },
        popEnterTransition = { ScreenMotion.enter(level, scheme, fromEnd = false) },
        popExitTransition = { ScreenMotion.exit(level, scheme, toEnd = true) },
        builder = builder,
    )
}

/**
 * A list-detail pane that comes and goes as the motion level says: on a phone the list
 * slides away to the start as a chat opens from the end, and both reverse on the way back.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ThreePaneScaffoldPaneScope.MotionPane(
    atEnd: Boolean,
    content: @Composable AnimatedPaneScope.() -> Unit,
) {
    val level = PingMeTheme.motion
    val scheme = MaterialTheme.motionScheme
    AnimatedPane(
        enterTransition = ScreenMotion.enter(level, scheme, fromEnd = atEnd),
        exitTransition = ScreenMotion.exit(level, scheme, toEnd = atEnd),
        content = content,
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
