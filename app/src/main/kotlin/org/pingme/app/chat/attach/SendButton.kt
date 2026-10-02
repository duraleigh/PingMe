// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.core.ui.theme.Haptics
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.R as UiR

/**
 * The send button (UI_DESIGN.md 2.2, 3.2): tap to send; press and hold for Send later (10.13).
 * One button: the owner found the Expressive split button too easy to mis-tap (Gate G1). There
 * is no Send as SMS: Google Messages does not let a paired device choose it (owner, 2026-10-01).
 */
@Composable
fun SendButton(
    enabled: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onSendLater: (() -> Unit)? = null,
) {
    val label = stringResource(R.string.chat_send)
    val moreLabel = stringResource(R.string.chat_send_options)
    val more = onSendLater != null
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val haptics = PingMeTheme.appearance.haptics
    val colours = IconButtonDefaults.filledIconButtonColors()
    Box(modifier) {
        Box(
            Modifier
                .size(SEND_SIZE)
                .clip(CircleShape)
                .background(if (enabled) colours.containerColor else colours.disabledContainerColor)
                .semantics { contentDescription = label }
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onLongClickLabel = moreLabel.takeIf { more },
                    onLongClick =
                        if (more) {
                            {
                                if (haptics != Haptics.OFF) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                open = true
                            }
                        } else {
                            null
                        },
                    onClick = onSend,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(UiR.drawable.ic_send),
                null,
                tint = if (enabled) colours.contentColor else colours.disabledContentColor,
            )
        }
        SendOptions(open, { open = false }, onSendLater)
    }
}

/** What holding Send offers: Send later (10.13). */
@Composable
private fun SendOptions(
    open: Boolean,
    onClose: () -> Unit,
    onSendLater: (() -> Unit)?,
) {
    DropdownMenu(open, onClose) {
        onSendLater?.let { later ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.later_title)) },
                leadingIcon = { Icon(painterResource(UiR.drawable.ic_schedule_send), null) },
                onClick = {
                    onClose()
                    later()
                },
            )
        }
    }
}

private val SEND_SIZE = 52.dp
