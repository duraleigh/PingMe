// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import androidx.core.net.toUri
import org.pingme.app.R
import org.pingme.app.chat.search.ChatSearchState
import org.pingme.app.chat.search.SearchHooks
import org.pingme.core.model.CallMethod
import org.pingme.core.model.MessageId

// The chat route's wiring, kept apart so the route itself stays short.

/** The header's buttons; [onNotice] shows a snackbar when a call cannot be placed here. */
internal fun headerActions(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: (() -> Unit)?,
    context: android.content.Context,
    onNotice: (Int) -> Unit,
) = HeaderActions(
    onBack = onBack,
    onSearch = viewModel.search::open,
    onFilter = viewModel::setFilter,
    onCall = { video ->
        when (placeCall(context, state, video)) {
            CallOutcome.CALLING -> Unit
            CallOutcome.OPENED_APP -> onNotice(R.string.chat_call_opened_app)
            CallOutcome.UNAVAILABLE -> onNotice(R.string.chat_calls_unavailable)
        }
    },
    onProfile =
        state.profileUrl?.let { url ->
            { if (!openPage(context, url)) onNotice(R.string.chat_profile_unavailable) }
        },
)

/** Opens [url] in whatever handles it (the Instagram app for a profile page); false when nothing can. */
private fun openPage(
    context: android.content.Context,
    url: String,
): Boolean =
    try {
        context.startActivity(
            android.content
                .Intent(android.content.Intent.ACTION_VIEW, url.toUri())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

/** The same header actions, with the name and "Chat details" opening [onDetails]. */
internal fun HeaderActions.copyWithDetails(onDetails: (() -> Unit)?) =
    HeaderActions(onBack, onCall, onDetails, onSearch, onFilter, onProfile)

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

private fun placeCall(
    context: android.content.Context,
    state: ChatUiState,
    video: Boolean,
): CallOutcome {
    // A merged chat calls on the network the composer is set to (UI_DESIGN.md 10.17).
    val network =
        state.members.firstOrNull { it.chatId == state.sendVia }?.network
            ?: state.account?.network
            ?: return CallOutcome.UNAVAILABLE
    val calls = state.capabilities?.calls ?: return CallOutcome.UNAVAILABLE
    val method = if (video) calls.video else calls.audio
    if (method == CallMethod.NONE) return CallOutcome.UNAVAILABLE
    return Calls(context).start(network, method, video, state.phone)
}
