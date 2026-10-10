// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import org.pingme.app.R
import org.pingme.app.chat.search.ChatSearchState
import org.pingme.app.chat.search.SearchHooks
import org.pingme.core.connector.remoteId
import org.pingme.core.model.CallMethod
import org.pingme.core.model.MessageId

// The chat route's wiring, kept apart so the route itself stays short.

/** The header's buttons; [notices] carries the snackbar for whatever cannot be done here, and the call placer. */
internal fun headerActions(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: (() -> Unit)?,
    onDetails: (() -> Unit)?,
    context: android.content.Context,
    notices: ChatNotices,
) = HeaderActions(
    onBack = onBack,
    onDetails = onDetails,
    onSearch = viewModel.search::open,
    onFilter = viewModel::setFilter,
    onCall = { video ->
        callRequest(state, video)?.let(notices.placeCall) ?: notices.notice(R.string.chat_calls_unavailable)
    },
    onProfile =
        state.profileUrl?.let { url ->
            { if (!openPage(context, url)) notices.notice(R.string.chat_profile_unavailable) }
        },
)

/** The chat route's snackbar and the two ways of speaking into it: a string resource, or a call's outcome. */
internal class ChatNotices(
    val snackbar: SnackbarHostState,
    val notice: (Int) -> Unit,
    val placeCall: (CallRequest) -> Unit,
)

@Composable
internal fun rememberChatNotices(): ChatNotices {
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
    return ChatNotices(snackbar, { say(resources.getString(it)) }, rememberCallPlacer(say))
}

/**
 * Opens [url] in the Instagram app when it is installed, else in whatever handles it
 * (2026-10-05: the phone sent the page to Chrome); false when nothing can.
 */
private fun openPage(
    context: android.content.Context,
    url: String,
): Boolean = openWith(context, url, INSTAGRAM_PACKAGE) || openWith(context, url, null)

private fun openWith(
    context: android.content.Context,
    url: String,
    appPackage: String?,
): Boolean =
    try {
        context.startActivity(
            android.content
                .Intent(android.content.Intent.ACTION_VIEW, url.toUri())
                .setPackage(appPackage)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

private const val INSTAGRAM_PACKAGE = "com.instagram.android"

/** Search in chat: picking a result or a date closes search and jumps there (UI_DESIGN.md 10.14). */
internal fun searchHooks(
    viewModel: ChatViewModel,
    state: ChatSearchState,
    jump: MessageId?,
) = SearchHooks(
    viewModel.search,
    state,
    onOpen = {
        viewModel.search.close()
        viewModel.jumps.to(it)
    },
    onDate = {
        viewModel.search.close()
        viewModel.jumps.toDate(it)
    },
    jump = jump,
    onJumped = viewModel.jumps::done,
)

/** What the phone or video icon calls, or null when this chat cannot be called from here. */
internal fun callRequest(
    state: ChatUiState,
    video: Boolean,
): CallRequest? {
    // A merged chat calls on the network the composer is set to (UI_DESIGN.md 10.17).
    val member = state.members.firstOrNull { it.chatId == state.sendVia }
    val network = member?.network ?: state.account?.network ?: return null
    val calls = state.capabilities?.calls ?: return null
    val method = if (video) calls.video else calls.audio
    if (method == CallMethod.NONE) return null
    val thread = (member?.chatId ?: state.chat?.id)?.remoteId
    return CallRequest(network, method, video, CallTarget(state.phone, thread))
}
