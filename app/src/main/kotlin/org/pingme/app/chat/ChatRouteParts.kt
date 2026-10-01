// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

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
    onCall = { video ->
        when (placeCall(context, state, video)) {
            CallOutcome.CALLING -> Unit
            CallOutcome.OPENED_APP -> onNotice(R.string.chat_call_opened_app)
            CallOutcome.UNAVAILABLE -> onNotice(R.string.chat_calls_unavailable)
        }
    },
)

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
    val network = state.account?.network ?: return CallOutcome.UNAVAILABLE
    val calls = state.capabilities?.calls ?: return CallOutcome.UNAVAILABLE
    val method = if (video) calls.video else calls.audio
    if (method == CallMethod.NONE) return CallOutcome.UNAVAILABLE
    return Calls(context).start(network, method, video, state.phone)
}
