// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import org.pingme.app.R
import org.pingme.app.notify.Choose
import org.pingme.app.notify.SoundRows
import org.pingme.core.model.ChatOverrides
import org.pingme.core.ui.components.SettingsSectionHeader
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Mute for a while, and this chat's own sound and vibration (UI_DESIGN.md 3.4, 6.1). */
@Composable
fun DetailsNotifications(
    state: ChatDetailsState,
    viewModel: NotificationChoices,
    modifier: Modifier = Modifier,
) {
    val chat = state.chat ?: return
    val overrides = state.overrides ?: ChatOverrides(chat.id)
    var muting by remember { mutableStateOf(false) }
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.details_notifications))
        val until = chat.muteUntil
        ListItem(
            headlineContent = { Text(stringResource(R.string.details_mute_for)) },
            supportingContent = {
                when {
                    !chat.isMuted -> Unit
                    until == null -> Text(stringResource(R.string.details_muted_forever))
                    else -> Text(stringResource(R.string.details_muted_until, shortDateTime(until)))
                }
            },
            modifier = Modifier.selectable(false, onClick = { muting = true }, role = Role.Button),
        )
        SoundRows(overrides.soundUri, overrides.vibration, viewModel.onNotification)
    }
    if (muting) {
        Choose(R.string.details_mute_for, MUTES, null, { stringResource(it.first) }, { viewModel.onMute(it.second) }) {
            muting = false
        }
    }
}

private fun shortDateTime(at: kotlin.time.Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(
            java.time.Instant
                .ofEpochMilli(at.toEpochMilliseconds())
                .atZone(ZoneId.systemDefault()),
        )

private val MUTES =
    listOf(
        R.string.details_mute_hour to 1.hours,
        R.string.details_mute_8_hours to 8.hours,
        R.string.details_mute_week to 7.days,
        R.string.details_mute_forever to null,
    )
