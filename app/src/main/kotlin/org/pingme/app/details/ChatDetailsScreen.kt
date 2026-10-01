// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pingme.app.R
import org.pingme.app.chat.search.SearchType
import org.pingme.core.model.ChatId
import org.pingme.core.ui.R as UiR

/** Where Chat details goes: back to the chat, or out of it once the chat is gone. */
class DetailsNavigation(
    val onBack: () -> Unit,
    /** Back to the chat, after asking it to search or show a message. */
    val onBackToChat: () -> Unit,
    /** The chat was blocked or deleted, so back past it. */
    val onLeft: () -> Unit,
)

/** Chat details with its view model (UI_DESIGN.md 3.4). */
@Composable
fun ChatDetailsRoute(
    chatId: ChatId,
    navigation: DetailsNavigation,
    modifier: Modifier = Modifier,
    viewModel: ChatDetailsViewModel =
        hiltViewModel<ChatDetailsViewModel, ChatDetailsViewModel.Factory>(
            key = chatId.value,
        ) { it.create(chatId.value) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.notices.collect { snackbar.showSnackbar(it) } }
    val toChat = { ask: () -> Unit ->
        ask()
        navigation.onBackToChat()
    }
    ChatDetailsScreen(
        state = state,
        sections =
            DetailsSections(
                header =
                    HeaderButtons(
                        onSearch = { toChat { viewModel.askSearch(SearchType.TEXT) } },
                        onMute = { viewModel.mute(null) },
                        onUnmute = viewModel::unmute,
                        onPin = viewModel::setPinned,
                        onRename = viewModel::rename,
                    ),
                onSeeAll = { type -> toChat { viewModel.askSearch(type) } },
                onOpen = { message -> toChat { viewModel.askJump(message) } },
                notifications = NotificationChoices(viewModel::mute, viewModel::setNotification),
                look = LookChoices(viewModel::setLook, viewModel::importWallpaper),
                onReactions = viewModel::setReactions,
                onUnpin = viewModel::unpin,
                onPhoto = viewModel::setAvatar,
                chat =
                    ChatChoices(
                        onObscured = viewModel::setObscured,
                        onLowPriority = viewModel::setLowPriority,
                        onFolder = viewModel::moveTo,
                        onArchived = viewModel::setArchived,
                        onBlock = { viewModel.block(navigation.onLeft) },
                        onDelete = { viewModel.delete(navigation.onLeft) },
                    ),
            ),
        onBack = navigation.onBack,
        snackbar = snackbar,
        modifier = modifier,
    )
}

/** What every section of Chat details can do. */
class DetailsSections(
    val header: HeaderButtons,
    val onSeeAll: (SearchType) -> Unit,
    val onOpen: (org.pingme.core.model.Message) -> Unit,
    val notifications: NotificationChoices,
    val look: LookChoices,
    val onReactions: (List<String>?) -> Unit,
    val onUnpin: (org.pingme.core.model.Message) -> Unit,
    val onPhoto: (org.pingme.core.model.AvatarSource) -> Unit,
    val chat: ChatChoices,
)

/**
 * Chat details (UI_DESIGN.md 3.4): the person or group at the top, then media, notifications,
 * look, reactions, members, pinned messages, photo, privacy, and the chat's actions.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatDetailsScreen(
    state: ChatDetailsState,
    sections: DetailsSections,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.details_title)) },
                navigationIcon = {
                    IconButton(
                        onBack,
                    ) { Icon(painterResource(UiR.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = padding) {
            item { DetailsHeader(state, sections.header) }
            item { DetailsMedia(state, sections.onSeeAll, sections.onOpen) }
            item { DetailsNotifications(state, sections.notifications) }
            item { DetailsLook(state, sections.look) }
            item { DetailsReactions(state, sections.onReactions) }
            item { DetailsMembers(state) }
            item { DetailsPinned(state, sections.onOpen, sections.onUnpin) }
            item { DetailsPhoto(state, sections.onPhoto) }
            item { DetailsPrivacy(state, sections.chat) }
            item { DetailsChatActions(state, sections.chat) }
        }
    }
}
