// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import org.pingme.app.R
import org.pingme.core.ui.R as UiR

/**
 * The send button (UI_DESIGN.md 3.2). Where there are more ways to send, it is a split
 * button whose menu holds them: Send later (10.13) and, in Google Messages chats, Send as
 * SMS. Holding the send half also opens Send later.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SendButton(
    enabled: Boolean,
    onSend: () -> Unit,
    onSendSms: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSendLater: (() -> Unit)? = null,
) {
    val label = stringResource(R.string.chat_send)
    if (onSendSms == null && onSendLater == null) {
        FilledIconButton(onSend, modifier.size(SEND_SIZE), enabled = enabled) {
            Icon(painterResource(UiR.drawable.ic_send), label)
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onSend,
                    Modifier.height(SEND_SIZE).onLongPress(onSendLater?.takeIf { enabled }),
                    enabled = enabled,
                ) { Icon(painterResource(UiR.drawable.ic_send), label) }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(open, { open = it }, Modifier.height(SEND_SIZE), enabled = enabled) {
                    Icon(painterResource(UiR.drawable.ic_expand_more), stringResource(R.string.chat_send_options))
                }
            },
        )
        DropdownMenu(open, { open = false }) {
            onSendLater?.let { later ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.later_title)) },
                    leadingIcon = { Icon(painterResource(UiR.drawable.ic_schedule_send), null) },
                    onClick = {
                        open = false
                        later()
                    },
                )
            }
            onSendSms?.let { sms ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_send_sms)) },
                    leadingIcon = { Icon(painterResource(UiR.drawable.ic_sms), null) },
                    onClick = {
                        open = false
                        sms()
                    },
                )
            }
        }
    }
}

// A long press runs [action] and swallows the rest of the touch, so the button's own tap does not fire.
@Composable
private fun Modifier.onLongPress(action: (() -> Unit)?): Modifier {
    val current by rememberUpdatedState(action)
    if (action == null) return this
    return pointerInput(Unit) {
        val hold = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial)
            val lifted = withTimeoutOrNull(hold) { waitForUpOrCancellation(PointerEventPass.Initial) }
            if (lifted == null) {
                current?.invoke()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}

private val SEND_SIZE = 52.dp
