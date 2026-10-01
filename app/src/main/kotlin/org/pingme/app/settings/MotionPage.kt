// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.pingme.app.R
import org.pingme.app.appearance.MotionSection
import org.pingme.core.ui.components.SwitchSetting
import org.pingme.core.ui.theme.MotionIntensity

/** Settings > Motion (UI_DESIGN.md 4.5, 10.8): how much moves, flippy reactions, and haptics. */
@Composable
fun MotionPage(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        MotionSection(state.appearance, actions::updateAppearance)
        SwitchSetting(
            stringResource(R.string.motion_flippy),
            flippyOn(state.app.flippyReactions, state.appearance.motion),
            { on -> actions.update { it.copy(flippyReactions = on) } },
            description = stringResource(R.string.motion_flippy_note),
        )
    }
}

/** Flippy reactions: the user's choice, or on at Full and Extra and off at Subtle and Off (UI_DESIGN.md 10.8). */
fun flippyOn(
    choice: Boolean?,
    motion: MotionIntensity,
) = choice ?: (motion == MotionIntensity.FULL || motion == MotionIntensity.EXTRA)
