// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.pingme.app.appearance.AppearanceActions
import org.pingme.app.appearance.AppearanceScreen
import org.pingme.app.chat.ChatScreen
import org.pingme.app.chat.ChatScreenActions
import org.pingme.app.chat.ChatUiState
import org.pingme.app.chat.HeaderActions
import org.pingme.app.chat.chatItems
import org.pingme.app.details.ChatChoices
import org.pingme.app.details.ChatDetailsScreen
import org.pingme.app.details.ChatDetailsState
import org.pingme.app.details.DetailsSections
import org.pingme.app.details.HeaderButtons
import org.pingme.app.details.LookChoices
import org.pingme.app.details.NotificationChoices
import org.pingme.app.inbox.ChatListRoute
import org.pingme.app.inbox.ChatListScreen
import org.pingme.app.inbox.ChatListUiState
import org.pingme.app.inbox.ListKind
import org.pingme.app.inbox.NewChatActions
import org.pingme.app.inbox.NewChatScreen
import org.pingme.app.inbox.NewChatUiState
import org.pingme.app.inbox.SearchResults
import org.pingme.app.inbox.SearchScreen
import org.pingme.app.inbox.previewInboxState
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import java.time.ZoneOffset

// Previews of the chat, its details, the lists, new chat, search, and the Appearance studio (BUILD_PLAN.md P2.8).

private val chatState =
    ChatUiState(
        loading = false,
        chat = PreviewData.chat,
        account = PreviewData.account,
        items = chatItems(PreviewData.messages, null, ZoneOffset.UTC),
        me = PreviewData.me,
    )

private val chatActions =
    ChatScreenActions(HeaderActions(onBack = {}, onCall = {}), {}, {}, {}, {}, {}, {}, {})

@Composable
private fun Themed(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) = PingMeTheme(Appearance(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT), content = content)

@Preview(name = "Chat, light", widthDp = 411, heightDp = 891)
@Composable
internal fun ChatPreview() = Themed { ChatScreen(chatState, chatActions) }

@Preview(name = "Chat, dark", widthDp = 411, heightDp = 891)
@Composable
internal fun ChatDarkPreview() = Themed(dark = true) { ChatScreen(chatState, chatActions) }

@Preview(name = "Chat details", widthDp = 411, heightDp = 891)
@Composable
internal fun ChatDetailsPreview() =
    Themed {
        ChatDetailsScreen(
            ChatDetailsState(PreviewData.chat, PreviewData.account, pinned = PreviewData.messages.take(1)),
            DetailsSections(
                HeaderButtons({}, {}, {}, {}, {}),
                {},
                {},
                NotificationChoices({}, { _, _ -> }),
                LookChoices({}, { _, _ -> }),
                {},
                {},
                {},
                ChatChoices({}, {}, {}, {}, {}, {}),
            ),
            onBack = {},
        )
    }

@Preview(name = "Archived list", widthDp = 411, heightDp = 891)
@Composable
internal fun ChatListPreview() =
    Themed {
        val inbox = previewInboxState()
        ChatListScreen(
            ChatListRoute(ListKind.ARCHIVED),
            ChatListUiState(inbox.rows, inbox.now, loading = false),
            {},
            {},
            { _, _ -> },
            { _, _ -> },
            { _, _ -> },
        )
    }

@Preview(name = "New group", widthDp = 411, heightDp = 891)
@Composable
internal fun NewChatPreview() =
    Themed {
        NewChatScreen(
            group = true,
            NewChatUiState(
                listOf(PreviewData.account),
                PreviewData.account.id,
                title = "Climbing",
                members = listOf("Sam Ortiz"),
            ),
            NewChatActions({}, {}, {}, {}, {}, {}, {}),
        )
    }

@Preview(name = "Search", widthDp = 411, heightDp = 891)
@Composable
internal fun SearchPreview() =
    Themed {
        SearchScreen(
            "dinner",
            SearchResults(
                listOf(PreviewData.chat),
                listOf(PreviewData.messages[3] to PreviewData.chat),
                PreviewData.now,
            ),
            {},
            {},
            {},
        )
    }

@Preview(name = "Appearance studio", widthDp = 411, heightDp = 891)
@Composable
internal fun AppearancePreview() =
    Themed { AppearanceScreen(Appearance(mode = ThemeMode.LIGHT), {}, AppearanceActions({}, {}, {}, {}, {}, {}, {})) }
