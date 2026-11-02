// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.chat.CallRequest
import org.pingme.app.chat.CallTarget
import org.pingme.app.chat.callNumber
import org.pingme.app.chat.rememberCallPlacer
import org.pingme.core.connector.remoteId
import org.pingme.core.model.CallMethod
import org.pingme.core.ui.R as UiR

/**
 * The buttons under the name, each a pill over its label, as on the owner's Google Messages:
 * Call, Video, Contact info, Search, and PingMe's own Mute and Pin (2026-11-01).
 */
@Composable
internal fun DetailsPills(
    state: ChatDetailsState,
    buttons: HeaderButtons,
    modifier: Modifier = Modifier,
) {
    val chat = state.chat ?: return
    val context = LocalContext.current
    val place = rememberCallPlacer { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    FlowRow(
        modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = PILLS_ACROSS,
    ) {
        callRequest(state, video = false)?.let {
            DetailsPill(UiR.drawable.ic_call, R.string.details_call) { place(it) }
        }
        callRequest(state, video = true)?.let {
            DetailsPill(UiR.drawable.ic_videocam, R.string.details_video) { place(it) }
        }
        ContactPill(state.person)
        DetailsPill(UiR.drawable.ic_search, R.string.details_search, buttons.onSearch)
        val muted = chat.isMuted
        DetailsPill(
            if (muted) UiR.drawable.ic_notifications else UiR.drawable.ic_notifications_off,
            if (muted) R.string.details_unmute else R.string.details_mute,
            if (muted) buttons.onUnmute else buttons.onMute,
        )
        DetailsPill(UiR.drawable.ic_push_pin, if (chat.isPinned) R.string.details_unpin else R.string.details_pin) {
            buttons.onPin(!chat.isPinned)
        }
    }
}

/** What calling this chat means, or null when the network cannot call from here. */
private fun callRequest(
    state: ChatDetailsState,
    video: Boolean,
): CallRequest? {
    val chat = state.chat ?: return null
    val network = state.account?.network ?: return null
    val calls = state.capabilities?.calls ?: return null
    val method = if (video) calls.video else calls.audio
    val phone = callNumber(chat.kind, chat.participants, chat.accountId, null, state.people)
    return CallRequest(network, method, video, CallTarget(phone, chat.id.remoteId)).takeIf { method != CallMethod.NONE }
}

@Composable
internal fun DetailsPill(
    icon: Int,
    label: Int,
    onClick: () -> Unit,
) {
    // The label is part of the target: the whole option takes the tap.
    Column(
        Modifier.width(PILL_WIDTH).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            Modifier.size(width = PILL_ICON_WIDTH, height = PILL_ICON_HEIGHT),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null) }
        }
        Text(
            stringResource(label),
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

private const val PILLS_ACROSS = 4
private val PILL_WIDTH = 84.dp
private val PILL_ICON_WIDTH = 72.dp
private val PILL_ICON_HEIGHT = 56.dp
