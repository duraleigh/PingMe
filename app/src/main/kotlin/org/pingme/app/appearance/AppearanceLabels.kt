// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import androidx.annotation.StringRes
import org.pingme.app.R
import org.pingme.core.ui.theme.BubbleStyle
import org.pingme.core.ui.theme.ContrastLevel
import org.pingme.core.ui.theme.Haptics
import org.pingme.core.ui.theme.InboxDensity
import org.pingme.core.ui.theme.MotionIntensity
import org.pingme.core.ui.theme.PinnedStyle
import org.pingme.core.ui.theme.SenderNameColors
import org.pingme.core.ui.theme.ShapeFamily
import org.pingme.core.ui.theme.SwipeAction
import org.pingme.core.ui.theme.ThemeMode
import org.pingme.core.ui.theme.TimestampMode

// The words for each appearance option.

@StringRes
fun ThemeMode.label() =
    when (this) {
        ThemeMode.LIGHT -> R.string.mode_light
        ThemeMode.DARK -> R.string.mode_dark
        ThemeMode.FOLLOW_SYSTEM -> R.string.mode_system
    }

@StringRes
fun ContrastLevel.label() =
    when (this) {
        ContrastLevel.STANDARD -> R.string.contrast_standard
        ContrastLevel.MEDIUM -> R.string.contrast_medium
        ContrastLevel.HIGH -> R.string.contrast_high
    }

@StringRes
fun SenderNameColors.label() =
    when (this) {
        SenderNameColors.AUTO -> R.string.sender_names_auto
        SenderNameColors.FIXED -> R.string.sender_names_fixed
    }

@StringRes
fun ShapeFamily.label() =
    when (this) {
        ShapeFamily.ROUND -> R.string.shape_round
        ShapeFamily.SOFT -> R.string.shape_soft
        ShapeFamily.SHARP -> R.string.shape_sharp
        ShapeFamily.EXPRESSIVE -> R.string.shape_expressive
    }

@StringRes
fun BubbleStyle.label() =
    when (this) {
        BubbleStyle.TONAL -> R.string.bubble_tonal
        BubbleStyle.OUTLINED -> R.string.bubble_outlined
        BubbleStyle.FILLED -> R.string.bubble_filled
        BubbleStyle.GRADIENT -> R.string.bubble_gradient
        BubbleStyle.PILL -> R.string.bubble_pill
    }

@StringRes
fun InboxDensity.label() =
    when (this) {
        InboxDensity.COMPACT -> R.string.density_compact
        InboxDensity.COMFORTABLE -> R.string.density_comfortable
        InboxDensity.SPACIOUS -> R.string.density_spacious
    }

@StringRes
fun PinnedStyle.label() =
    when (this) {
        PinnedStyle.ROW -> R.string.pinned_row
        PinnedStyle.GRID -> R.string.pinned_grid
        PinnedStyle.TOP_OF_LIST -> R.string.pinned_top
    }

@StringRes
fun TimestampMode.label() =
    when (this) {
        TimestampMode.ALWAYS -> R.string.timestamps_always
        TimestampMode.ON_TAP -> R.string.timestamps_on_tap
        TimestampMode.GROUPED -> R.string.timestamps_grouped
    }

@StringRes
fun MotionIntensity.label() =
    when (this) {
        MotionIntensity.OFF -> R.string.motion_off
        MotionIntensity.SUBTLE -> R.string.motion_subtle
        MotionIntensity.FULL -> R.string.motion_full
        MotionIntensity.EXTRA -> R.string.motion_extra
    }

@StringRes
fun Haptics.label() =
    when (this) {
        Haptics.OFF -> R.string.haptics_off
        Haptics.LIGHT -> R.string.haptics_light
        Haptics.STRONG -> R.string.haptics_strong
    }

@StringRes
fun SwipeAction.label() =
    when (this) {
        SwipeAction.PIN -> R.string.swipe_pin
        SwipeAction.ARCHIVE -> R.string.swipe_archive
        SwipeAction.MUTE -> R.string.swipe_mute
        SwipeAction.MARK_READ -> R.string.swipe_mark_read
        SwipeAction.LOW_PRIORITY -> R.string.swipe_low_priority
        SwipeAction.DELETE -> R.string.swipe_delete
        SwipeAction.OFF -> R.string.swipe_off
    }
