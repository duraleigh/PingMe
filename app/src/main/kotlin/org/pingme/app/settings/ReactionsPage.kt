// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.pingme.app.R
import org.pingme.app.chat.emoji.EmojiPickerSheet
import org.pingme.core.ui.components.SettingsSectionHeader

/** Which emoji list the full picker is adding to. */
private enum class Adding { QUICK, DOUBLE_TAP, SPECIAL }

/**
 * Settings > Reactions (UI_DESIGN.md 5.4, 10.5): the quick set (reorder, remove, add, up to
 * eight), the double-tap reaction, and the special emoji that glow at Extra motion.
 */
@Composable
fun ReactionsPage(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf<Adding?>(null) }
    val quick = state.quickReactions
    Column(modifier) {
        SettingsSectionHeader(stringResource(R.string.reactions_quick))
        Text(
            stringResource(R.string.reactions_quick_note),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        EmojiRow {
            quick.forEachIndexed { i, emoji -> QuickChip(emoji, i, quick, actions::setQuickReactions) }
            if (quick.size <
                MAX_QUICK
            ) {
                AssistChip({ adding = Adding.QUICK }, label = { Text(stringResource(R.string.reactions_add)) })
            }
        }
        SettingsSectionHeader(stringResource(R.string.reactions_double_tap))
        EmojiRow {
            (quick + state.doubleTap).distinct().forEach { emoji ->
                FilterChip(emoji == state.doubleTap, { actions.setDoubleTap(emoji) }, label = { Big(emoji) })
            }
            AssistChip(
                { adding = Adding.DOUBLE_TAP },
                label = { Text(stringResource(R.string.reactions_double_tap_pick)) },
            )
        }
        SettingsSectionHeader(stringResource(R.string.reactions_special))
        Text(
            stringResource(R.string.reactions_special_note),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        EmojiRow {
            state.app.specialEmoji.forEach { emoji ->
                FilterChip(
                    true,
                    { actions.update { it.copy(specialEmoji = it.specialEmoji - emoji) } },
                    label = { Big(emoji) },
                )
            }
            AssistChip({ adding = Adding.SPECIAL }, label = { Text(stringResource(R.string.reactions_add)) })
        }
    }
    adding?.let { target ->
        EmojiPickerSheet(state.recentEmoji, onPick = { emoji ->
            when (target) {
                Adding.QUICK -> if (emoji !in quick) actions.setQuickReactions(quick + emoji)
                Adding.DOUBLE_TAP -> actions.setDoubleTap(emoji)
                Adding.SPECIAL -> actions.update { it.copy(specialEmoji = it.specialEmoji + emoji) }
            }
        }, onDismiss = { adding = null })
    }
}

// One quick reaction; tapping it offers moving it or removing it.
@Composable
private fun QuickChip(
    emoji: String,
    index: Int,
    quick: List<String>,
    onSet: (List<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip({ open = true }, label = { Big(emoji) })
        DropdownMenu(open, { open = false }) {
            val swap = { to: Int ->
                onSet(quick.toMutableList().apply { add(to, removeAt(index)) })
                open = false
            }
            if (index >
                0
            ) {
                DropdownMenuItem({ Text(stringResource(R.string.reactions_move_earlier)) }, { swap(index - 1) })
            }
            if (index <
                quick.lastIndex
            ) {
                DropdownMenuItem({ Text(stringResource(R.string.reactions_move_later)) }, {
                    swap(index + 1)
                })
            }
            if (quick.size > 1) {
                DropdownMenuItem({ Text(stringResource(R.string.reactions_remove)) }, {
                    onSet(quick - emoji)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun EmojiRow(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
private fun Big(emoji: String) = Text(emoji, style = MaterialTheme.typography.titleLarge)

private const val MAX_QUICK = 8
