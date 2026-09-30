// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.pingme.app.appearance.AppearanceRoute
import org.pingme.app.chat.ChatPane
import org.pingme.app.chat.NoChatPicked
import org.pingme.app.inbox.ChatListRoute
import org.pingme.app.inbox.InboxNavigation
import org.pingme.app.inbox.InboxRoute
import org.pingme.app.inbox.MenuActions
import org.pingme.app.inbox.NewChatRoute
import org.pingme.app.inbox.SearchRoute
import org.pingme.app.inbox.route
import org.pingme.core.model.Account
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

@Composable
fun PingMeNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Home, modifier = modifier) {
        composable<Home> { InboxHome(nav) }
        composable<OpenChat> { entry ->
            val chats: ChatsViewModel = hiltViewModel()
            val id = ChatId(entry.arguments?.getString("chatId").orEmpty())
            ChatPane(id, chats.repository::chat, onBack = { nav.popBackStack() })
        }
        composable<AppearanceStudio> { AppearanceRoute(onBack = { nav.popBackStack() }) }
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
private fun InboxHome(
    nav: NavController,
    chats: ChatsViewModel = hiltViewModel(),
) {
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
                    ChatPane(
                        ChatId(id),
                        chats.repository::chat,
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

/** Gives screens the chat store without a view model of their own. */
@HiltViewModel
class ChatsViewModel
    @Inject
    constructor(
        val repository: ChatRepository,
    ) : ViewModel()
